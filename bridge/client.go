// SPDX-License-Identifier: AGPL-3.0-or-later
// Message Center's Android binding for mautrix-gmessages/libgm by Tulir Asokan.
package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"go.mau.fi/util/exhttp"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

// Sink receives protocol data inside the Android process. Implementations must
// enqueue callbacks and return promptly; never make an RPC from a callback.
type Sink interface{ OnEvent(kind, payload string) }

type Client struct {
	gm     *libgm.Client
	sink   Sink
	ctx    context.Context
	cancel context.CancelFunc
}

func NewClient(saved string, sink Sink) (*Client, error) {
	auth := libgm.NewAuthData()
	if saved != "" {
		if err := json.Unmarshal([]byte(saved), auth); err != nil {
			return nil, errors.New("Saved pairing could not be read. Pair again.")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	c := &Client{gm: libgm.NewClient(auth, nil, zerolog.Nop(), exhttp.SensibleClientSettings.WithGlobalTimeout(45*time.Second)), sink: sink, ctx: ctx, cancel: cancel}
	c.gm.SetEventHandler(c.handle)
	return c, nil
}
func marshal(p proto.Message) (string, error) {
	b, e := protojson.MarshalOptions{EmitDefaultValues: true}.Marshal(p)
	return string(b), e
}
func (c *Client) emit(kind, payload string) {
	if c.sink != nil {
		c.sink.OnEvent(kind, payload)
	}
}
func (c *Client) emitProto(kind string, p proto.Message) {
	if data, e := marshal(p); e == nil {
		c.emit(kind, data)
	}
}
func (c *Client) handle(evt any) {
	switch e := evt.(type) {
	case *libgm.WrappedMessage:
		data, err := marshal(e.Message)
		if err == nil {
			c.emit("MESSAGE", fmt.Sprintf(`{"old":%t,"message":%s}`, e.IsOld, data))
		}
	case *gmproto.Conversation:
		c.emitProto("CONVERSATION", e)
	case *events.ClientReady:
		for _, conv := range e.Conversations {
			c.emitProto("CONVERSATION", conv)
		}
		c.emit("READY", "{}")
	case *events.AuthTokenRefreshed:
		c.emit("SAVE_SESSION", "{}")
	case *events.GaiaLoggedOut:
		c.emit("AUTH_REQUIRED", "{}")
	case *events.ListenFatalError:
		c.emit("DISCONNECTED", "{}")
	case *events.ListenTemporaryError, *events.PhoneNotResponding, *events.PingFailed:
		c.emit("RECONNECTING", "{}")
	case *events.ListenRecovered, *events.PhoneRespondingAgain:
		c.emit("RECOVERED", "{}")
	}
}
func (c *Client) Pair(cookies string) error {
	var values map[string]string
	if err := json.Unmarshal([]byte(cookies), &values); err != nil {
		return errors.New("Google sign-in did not provide a usable session.")
	}
	for _, key := range []string{"SID", "HSID", "SSID", "APISID", "SAPISID"} {
		if values[key] == "" {
			return errors.New("Finish signing in to Google, then try pairing again.")
		}
	}
	c.gm.AuthData.SetCookies(values)
	ctx, cancel := context.WithTimeout(c.ctx, 3*time.Minute)
	defer cancel()
	emoji, ps, err := c.gm.StartGaiaPairing(ctx, c.ctx)
	if err != nil {
		return pairingError(err)
	}
	encoded, _ := json.Marshal(map[string]string{"emoji": emoji})
	c.emit("PAIR_EMOJI", string(encoded))
	if _, err = c.gm.FinishGaiaPairing(ctx, ps); err != nil {
		return pairingError(err)
	}
	// Finish pairing without libgm.DoGaiaPairing's asynchronous reconnect. The
	// Android owner starts exactly one connection after saving the new session.
	return nil
}
func pairingError(err error) error {
	switch {
	case errors.Is(err, libgm.ErrNoDevicesFound):
		return errors.New("Google found no phone. In Google Messages, open Device pairing and select the same Google account.")
	case errors.Is(err, libgm.ErrIncorrectEmoji):
		return errors.New("The selected emoji did not match. Start pairing again.")
	case errors.Is(err, libgm.ErrPairingCancelled):
		return errors.New("Pairing was cancelled on the phone.")
	case errors.Is(err, context.DeadlineExceeded), errors.Is(err, libgm.ErrPairingTimeout):
		return errors.New("Pairing timed out. Keep Google Messages open and try again.")
	case errors.Is(err, events.ErrInvalidCredentials):
		return errors.New("Google sign-in expired or was rejected. Sign in again.")
	default:
		return errors.New("Google pairing failed. Check your connection and the account selected in Google Messages.")
	}
}
func (c *Client) Start() error {
	if err := c.gm.Connect(c.ctx); err != nil {
		return errors.New("Google connection failed. Check your connection or pair again.")
	}
	return nil
}
func (c *Client) Close() { c.cancel(); c.gm.Disconnect() }
func (c *Client) ExportSession() (string, error) {
	b, e := json.Marshal(c.gm.AuthData)
	return string(b), e
}
func (c *Client) IsConnected() bool { return c.gm.IsConnected() }
func (c *Client) requestContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(c.ctx, 45*time.Second)
}
func parseCursor(raw string) (*gmproto.Cursor, error) {
	if raw == "" || raw == "null" {
		return nil, nil
	}
	p := &gmproto.Cursor{}
	if e := protojson.Unmarshal([]byte(raw), p); e != nil {
		return nil, errors.New("Invalid history cursor.")
	}
	return p, nil
}
func (c *Client) Chats(cursor string, folder int) (string, error) {
	p, e := parseCursor(cursor)
	if e != nil {
		return "", e
	}
	ctx, cancel := c.requestContext()
	defer cancel()
	if folder != 1 && folder != 2 {
		return "", errors.New("Unsupported folder.")
	}
	r, e := c.gm.ListConversations(ctx, &gmproto.ListConversationsRequest{Count: 50, Folder: gmproto.ListConversationsRequest_Folder(folder), Cursor: p})
	if e != nil {
		return "", errors.New("Could not load conversations from Google. Try Refresh.")
	}
	return marshal(r)
}
func (c *Client) Messages(id, cursor string) (string, error) {
	p, e := parseCursor(cursor)
	if e != nil {
		return "", e
	}
	ctx, cancel := c.requestContext()
	defer cancel()
	r, e := c.gm.FetchMessages(ctx, id, 100, p)
	if e != nil {
		return "", errors.New("Could not load this history page. Try again when connected.")
	}
	return marshal(r)
}
func (c *Client) Chat(id string) (string, error) {
	ctx, cancel := c.requestContext()
	defer cancel()
	r, e := c.gm.GetConversation(ctx, id)
	if e != nil {
		return "", errors.New("Could not refresh this conversation.")
	}
	return marshal(r)
}
func route(conv *gmproto.Conversation) (*gmproto.SIMPayload, error) {
	if conv.GetReadOnly() {
		return nil, errors.New("This conversation is read-only.")
	}
	id := conv.GetDefaultOutgoingID()
	if id == "" {
		return nil, errors.New("Google has not supplied a sending identity. Refresh this conversation.")
	}
	sim := conv.GetSimCard().GetSIMData().GetSIMPayload()
	if sim == nil {
		for _, p := range conv.GetParticipants() {
			if p.GetIsMe() && p.GetID().GetParticipantID() == id {
				sim = p.GetSimPayload()
				break
			}
		}
	}
	if sim == nil {
		return nil, errors.New("Google has not supplied a SIM route. Refresh this conversation.")
	}
	return sim, nil
}
func parseChat(raw string) (*gmproto.Conversation, error) {
	p := &gmproto.Conversation{}
	if e := protojson.Unmarshal([]byte(raw), p); e != nil {
		return nil, errors.New("Conversation could not be read.")
	}
	return p, nil
}
func (c *Client) SendText(chatJSON, text, replyID string) (string, error) {
	conv, e := parseChat(chatJSON)
	if e != nil {
		return "", e
	}
	sim, e := route(conv)
	if e != nil {
		return "", e
	}
	if strings.TrimSpace(text) == "" {
		return "", errors.New("Enter a message first.")
	}
	tmp := uuid.NewString()
	req := &gmproto.SendMessageRequest{ConversationID: conv.GetConversationID(), TmpID: tmp, SIMPayload: sim,
		MessagePayload: &gmproto.MessagePayload{TmpID: tmp, TmpID2: tmp, ConversationID: conv.GetConversationID(), ParticipantID: conv.GetDefaultOutgoingID(), MessageInfo: []*gmproto.MessageInfo{{Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: text}}}}}}
	if replyID != "" {
		req.Reply = &gmproto.ReplyPayload{MessageID: replyID}
	}
	ctx, cancel := c.requestContext()
	defer cancel()
	resp, e := c.gm.SendMessage(ctx, req)
	if e != nil {
		return "", errors.New("Send outcome unknown. Check the conversation in Google Messages before retrying.")
	}
	if resp.GetStatus() != gmproto.SendMessageResponse_SUCCESS {
		return "", errors.New("Google did not accept this message. Check Google Messages for details.")
	}
	return tmp, nil // Acceptance only. The real outgoing message arrives in history/stream.
}
func (c *Client) CreateChat(number string) (string, error) {
	if len(number) < 4 || number[0] != '+' {
		return "", errors.New("Use the full phone number with + and country code.")
	}
	for _, r := range number[1:] {
		if r < '0' || r > '9' {
			return "", errors.New("Use digits after + and the country code.")
		}
	}
	ctx, cancel := c.requestContext()
	defer cancel()
	req := &gmproto.GetOrCreateConversationRequest{Numbers: []*gmproto.ContactNumber{{Number: number, Number2: number, MysteriousInt: 2}}}
	resp, e := c.gm.GetOrCreateConversation(ctx, req)
	if e != nil {
		return "", errors.New("Conversation creation outcome unknown. Check Google Messages before trying again.")
	}
	if resp.GetStatus() == gmproto.GetOrCreateConversationResponse_CREATE_RCS {
		name := ""
		yes := true
		req.RCSGroupName = &name
		req.CreateRCSGroup = &yes
		resp, e = c.gm.GetOrCreateConversation(ctx, req)
		if e != nil {
			return "", errors.New("RCS conversation creation outcome unknown. Check Google Messages before trying again.")
		}
	}
	if resp.GetStatus() != gmproto.GetOrCreateConversationResponse_SUCCESS || resp.GetConversation().GetConversationID() == "" {
		return "", errors.New("Google could not create this conversation.")
	}
	return marshal(resp.GetConversation())
}
func (c *Client) React(chatJSON, messageID, emoji string, remove bool) error {
	conv, e := parseChat(chatJSON)
	if e != nil {
		return e
	}
	sim, e := route(conv)
	if e != nil {
		return e
	}
	action := gmproto.SendReactionRequest_ADD
	if remove {
		action = gmproto.SendReactionRequest_REMOVE
	}
	ctx, cancel := c.requestContext()
	defer cancel()
	r, e := c.gm.SendReaction(ctx, &gmproto.SendReactionRequest{MessageID: messageID, ReactionData: gmproto.MakeReactionData(emoji), Action: action, SIMPayload: sim})
	if e != nil {
		return errors.New("Reaction outcome unknown. Refresh before trying again.")
	}
	if !r.GetSuccess() {
		return errors.New("Google did not accept this reaction.")
	}
	return nil
}
