// SPDX-License-Identifier: AGPL-3.0-or-later
// Message Center's Android binding for mautrix-gmessages/libgm by Tulir Asokan.
package bridge

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
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
	case *gmproto.TypingData:
		c.emit("TYPING", typingPayload(e))
	case *gmproto.Settings:
		if payload, ok := settingsPayload(e); ok {
			c.emit("SETTINGS", payload)
		}
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
	// Gaia pairing opens a temporary long poll. Close it before the Android
	// owner starts the authenticated message connection (also on pairing failure).
	defer c.gm.Disconnect()
	ctx, cancel := context.WithTimeout(c.ctx, 3*time.Minute)
	defer cancel()
	// The account config supplies Google's browser device ID. Pairing with the
	// zero UUID from NewAuthData would reuse a placeholder identity.
	if err := c.gm.FetchConfig(ctx); err != nil {
		return errors.New("Google account configuration could not be loaded. Finish signing in and try again.")
	}
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
	c.gm.AuthData.CookiesLock.RLock()
	defer c.gm.AuthData.CookiesLock.RUnlock()
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
	if folder != 1 && folder != 2 && folder != 5 { // inbox, archive, spam & blocked
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
	if strings.TrimSpace(text) == "" {
		return "", errors.New("Enter a message first.")
	}
	return c.send(chatJSON, replyID, []*gmproto.MessageInfo{textInfo(text)})
}

// SendMedia uploads one file and sends it, with optional caption text in the
// same message (as Google Messages does for RCS; MMS carries both parts).
func (c *Client) SendMedia(chatJSON, path, fileName, mime, caption, replyID string) (string, error) {
	data, e := os.ReadFile(path)
	if e != nil {
		return "", errors.New("The attachment could not be read.")
	}
	if len(data) == 0 {
		return "", errors.New("The attachment is empty.")
	}
	if len(data) > maxUpload {
		return "", errors.New("Attachments can be up to 100 MB.")
	}
	media, e := c.gm.UploadMedia(data, fileName, mime)
	if e != nil {
		return "", errors.New("The attachment could not be uploaded. Check your connection and try again.")
	}
	return c.send(chatJSON, replyID, mediaInfos(media, caption))
}

const maxUpload = 100 << 20
const maxDownload = 250 << 20

func textInfo(text string) *gmproto.MessageInfo {
	return &gmproto.MessageInfo{Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: text}}}
}

func mediaInfos(media *gmproto.MediaContent, caption string) []*gmproto.MessageInfo {
	infos := []*gmproto.MessageInfo{{Data: &gmproto.MessageInfo_MediaContent{MediaContent: media}}}
	if strings.TrimSpace(caption) != "" {
		infos = append(infos, textInfo(caption))
	}
	return infos
}

func (c *Client) send(chatJSON, replyID string, infos []*gmproto.MessageInfo) (string, error) {
	conv, e := parseChat(chatJSON)
	if e != nil {
		return "", e
	}
	sim, e := route(conv)
	if e != nil {
		return "", e
	}
	tmp := uuid.NewString()
	req := &gmproto.SendMessageRequest{ConversationID: conv.GetConversationID(), TmpID: tmp, SIMPayload: sim,
		MessagePayload: &gmproto.MessagePayload{TmpID: tmp, TmpID2: tmp, ConversationID: conv.GetConversationID(), ParticipantID: conv.GetDefaultOutgoingID(), MessageInfo: infos}}
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

// DownloadMedia saves one attachment, already decrypted, to path. keyB64 is the
// message's decryptionKey as it appears in the JSON (base64). The file is written
// to path+".part" first, so a failed download never leaves a broken file behind.
func (c *Client) DownloadMedia(mediaID, keyB64, path string) error {
	key, e := mediaKey(keyB64)
	if mediaID == "" || e != nil {
		return errors.New("This attachment is missing its download details.")
	}
	body, e := c.gm.DownloadMedia(mediaID, key)
	if e != nil {
		return errors.New("The attachment could not be downloaded. Check your connection and try again.")
	}
	defer body.Close()
	return saveStream(body, path, maxDownload)
}

func mediaKey(keyB64 string) ([]byte, error) {
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.URLEncoding, base64.RawStdEncoding, base64.RawURLEncoding} {
		if key, e := enc.DecodeString(keyB64); e == nil && len(key) > 0 {
			return key, nil
		}
	}
	return nil, errors.New("invalid key")
}

func saveStream(r io.Reader, path string, limit int64) error {
	part := path + ".part"
	f, e := os.Create(part)
	if e != nil {
		return errors.New("There is no room to save this attachment.")
	}
	n, e := io.Copy(f, io.LimitReader(r, limit+1))
	if ce := f.Close(); e == nil {
		e = ce
	}
	if e == nil && n > limit {
		e = errors.New("too large")
	}
	if e == nil && n == 0 {
		e = errors.New("empty")
	}
	if e != nil {
		_ = os.Remove(part)
		if n > limit {
			return errors.New("This attachment is too large to download here.")
		}
		return errors.New("The attachment could not be downloaded. Check your connection and try again.")
	}
	return os.Rename(part, path)
}

func (c *Client) CreateChat(number string) (string, error) {
	return c.createConversation([]string{number}, "")
}

// CreateGroup starts a group with several people (comma-separated numbers).
// The name is used for RCS groups; MMS groups have no name.
func (c *Client) CreateGroup(numbersCSV, name string) (string, error) {
	var numbers []string
	for _, n := range strings.Split(numbersCSV, ",") {
		if n = strings.TrimSpace(n); n != "" {
			numbers = append(numbers, n)
		}
	}
	if len(numbers) < 2 {
		return "", errors.New("Pick at least two people for a group.")
	}
	return c.createConversation(numbers, strings.TrimSpace(name))
}

func validNumber(number string) error {
	if len(number) < 4 || number[0] != '+' {
		return errors.New("Use the full phone number with + and country code.")
	}
	for _, r := range number[1:] {
		if r < '0' || r > '9' {
			return errors.New("Use digits after + and the country code.")
		}
	}
	return nil
}

func (c *Client) createConversation(numbers []string, name string) (string, error) {
	req := &gmproto.GetOrCreateConversationRequest{}
	for _, n := range numbers {
		if e := validNumber(n); e != nil {
			return "", e
		}
		req.Numbers = append(req.Numbers, &gmproto.ContactNumber{Number: n, Number2: n, MysteriousInt: 2})
	}
	ctx, cancel := c.requestContext()
	defer cancel()
	resp, e := c.gm.GetOrCreateConversation(ctx, req)
	if e != nil {
		return "", errors.New("Conversation creation outcome unknown. Check Google Messages before trying again.")
	}
	if resp.GetStatus() == gmproto.GetOrCreateConversationResponse_CREATE_RCS {
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

// Contacts returns {"top":[...],"all":[...]} from the phone's contacts.
func (c *Client) Contacts() (string, error) {
	ctx, cancel := c.requestContext()
	defer cancel()
	all, e := c.gm.ListContacts(ctx)
	if e != nil {
		return "", errors.New("Contacts could not be loaded from your phone.")
	}
	top, _ := c.gm.ListTopContacts(ctx)
	marshalList := func(list []*gmproto.Contact) []json.RawMessage {
		out := []json.RawMessage{}
		for _, ct := range list {
			if raw, err := marshal(ct); err == nil {
				out = append(out, json.RawMessage(raw))
			}
		}
		return out
	}
	b, e := json.Marshal(map[string]any{"top": marshalList(top.GetContacts()), "all": marshalList(all.GetContacts())})
	if e != nil {
		return "", errors.New("Contacts could not be read.")
	}
	return string(b), nil
}

// ArchiveChat moves a conversation into Archived, or back to the inbox.
func (c *Client) ArchiveChat(conversationID string, archive bool) error {
	return c.updateConversation(archiveRequest(conversationID, archive), "Google could not archive this conversation.")
}

// MuteChat silences (or unsilences) a conversation's notifications in Google Messages.
func (c *Client) MuteChat(conversationID string, mute bool) error {
	return c.updateConversation(muteRequest(conversationID, mute), "Google could not change this conversation's notifications.")
}

// DeleteChat deletes the conversation from the phone.
func (c *Client) DeleteChat(chatJSON string) error {
	conv, e := parseChat(chatJSON)
	if e != nil {
		return e
	}
	return c.updateConversation(&gmproto.UpdateConversationRequest{Action: gmproto.ConversationActionStatus_DELETE, ConversationID: conv.GetConversationID(),
		Data: &gmproto.UpdateConversationRequest_DeleteData{DeleteData: &gmproto.DeleteConversationData{ConversationID: conv.GetConversationID(), Phone: nonEmpty(otherPhone(conv))}}},
		"Google could not delete this conversation.")
}

// BlockChat blocks ("block"), blocks and reports spam ("report"), or unblocks ("unblock").
func (c *Client) BlockChat(chatJSON, action string) error {
	conv, e := parseChat(chatJSON)
	if e != nil {
		return e
	}
	req, e := blockRequest(conv, action)
	if e != nil {
		return e
	}
	return c.updateConversation(req, "Google could not update blocking for this conversation.")
}

// DeleteMessage deletes one message from the phone (for you only).
func (c *Client) DeleteMessage(messageID string) error {
	ctx, cancel := c.requestContext()
	defer cancel()
	resp, e := c.gm.DeleteMessage(ctx, messageID)
	if e != nil || !resp.GetSuccess() {
		return errors.New("Google could not delete this message.")
	}
	return nil
}

func (c *Client) updateConversation(req *gmproto.UpdateConversationRequest, failure string) error {
	ctx, cancel := c.requestContext()
	defer cancel()
	resp, e := c.gm.UpdateConversation(ctx, req)
	if e != nil || !resp.GetSuccess() {
		return errors.New(failure)
	}
	return nil
}

func archiveRequest(conversationID string, archive bool) *gmproto.UpdateConversationRequest {
	status := gmproto.ConversationStatus_ACTIVE
	if archive {
		status = gmproto.ConversationStatus_ARCHIVED
	}
	return &gmproto.UpdateConversationRequest{ConversationID: conversationID, Data: &gmproto.UpdateConversationRequest_UpdateData{UpdateData: &gmproto.UpdateConversationData{
		ConversationID: conversationID, Data: &gmproto.UpdateConversationData_Status{Status: status}}}}
}

func muteRequest(conversationID string, mute bool) *gmproto.UpdateConversationRequest {
	state := gmproto.ConversationMuteStatus_UNMUTE
	if mute {
		state = gmproto.ConversationMuteStatus_MUTE
	}
	return &gmproto.UpdateConversationRequest{ConversationID: conversationID, Data: &gmproto.UpdateConversationRequest_UpdateData{UpdateData: &gmproto.UpdateConversationData{
		ConversationID: conversationID, Data: &gmproto.UpdateConversationData_Mute{Mute: state}}}}
}

func blockRequest(conv *gmproto.Conversation, action string) (*gmproto.UpdateConversationRequest, error) {
	kind := gmproto.ConversationActionStatus_UNBLOCK
	var a5 *gmproto.ConversationAction5
	switch action {
	case "block":
		kind, a5 = gmproto.ConversationActionStatus_BLOCK, &gmproto.ConversationAction5{Field2: true}
	case "report":
		kind, a5 = gmproto.ConversationActionStatus_BLOCK_AND_REPORT, &gmproto.ConversationAction5{Field2: true}
	case "unblock":
	default:
		return nil, errors.New("Unknown blocking action.")
	}
	id := conv.GetConversationID()
	return &gmproto.UpdateConversationRequest{Action: kind, ConversationID: id, Action5: a5,
		Data: &gmproto.UpdateConversationRequest_DeleteData{DeleteData: &gmproto.DeleteConversationData{ConversationID: id, Phone: nonEmpty(otherPhone(conv))}}}, nil
}

// otherPhone is the other person's number in a one-to-one chat; groups have none.
func otherPhone(conv *gmproto.Conversation) string {
	if conv.GetIsGroupChat() {
		return ""
	}
	for _, p := range conv.GetParticipants() {
		if p.GetIsVisible() && !p.GetIsMe() && p.GetID().GetNumber() != "" {
			return p.GetID().GetNumber()
		}
	}
	return ""
}

func nonEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// React adds, removes, or switches this user's reaction. Google keeps one
// reaction per person per message, so changing it uses "switch".
func (c *Client) React(chatJSON, messageID, emoji, action string) error {
	conv, e := parseChat(chatJSON)
	if e != nil {
		return e
	}
	sim, e := route(conv)
	if e != nil {
		return e
	}
	kind, e := reactionAction(action)
	if e != nil {
		return e
	}
	ctx, cancel := c.requestContext()
	defer cancel()
	r, e := c.gm.SendReaction(ctx, &gmproto.SendReactionRequest{MessageID: messageID, ReactionData: gmproto.MakeReactionData(emoji), Action: kind, SIMPayload: sim})
	if e != nil {
		return errors.New("Reaction outcome unknown. Refresh before trying again.")
	}
	if !r.GetSuccess() {
		return errors.New("Google did not accept this reaction.")
	}
	return nil
}

// settingsPayload forwards only the phone's RCS read-receipt switch. Settings
// events also describe SIM cards, which the app does not need.
func settingsPayload(s *gmproto.Settings) (string, bool) {
	rcs := s.GetRCSSettings()
	if rcs == nil {
		return "", false
	}
	b, _ := json.Marshal(map[string]bool{"rcsEnabled": rcs.GetIsEnabled(), "sendReadReceipts": rcs.GetSendReadReceipts()})
	return string(b), true
}

// MarkRead marks a conversation read on the phone up to messageID. Google
// Messages sends RCS read receipts for it when its own setting allows them.
func (c *Client) MarkRead(conversationID, messageID string) error {
	if conversationID == "" || messageID == "" {
		return errors.New("Nothing to mark as read.")
	}
	ctx, cancel := c.requestContext()
	defer cancel()
	if e := c.gm.MarkRead(ctx, conversationID, messageID); e != nil {
		return errors.New("Could not mark this conversation as read.")
	}
	return nil
}

// Thumbnails returns {participantID: base64 image} for the comma-separated
// participant IDs. Participants without a photo are omitted.
func (c *Client) Thumbnails(participantIDs string) (string, error) {
	ids := splitIDs(participantIDs)
	if len(ids) == 0 {
		return "{}", nil
	}
	ctx, cancel := c.requestContext()
	defer cancel()
	r, e := c.gm.GetParticipantThumbnail(ctx, ids...)
	if e != nil {
		return "", errors.New("Could not load contact photos.")
	}
	return thumbnailJSON(r), nil
}
func splitIDs(raw string) []string {
	var ids []string
	for _, id := range strings.Split(raw, ",") {
		if id = strings.TrimSpace(id); id != "" {
			ids = append(ids, id)
		}
	}
	return ids
}
func thumbnailJSON(r *gmproto.GetThumbnailResponse) string {
	out := map[string]string{}
	for _, t := range r.GetThumbnail() {
		if b := t.GetData().GetImageBuffer(); len(b) > 0 && t.GetIdentifier() != "" {
			out[t.GetIdentifier()] = base64.StdEncoding.EncodeToString(b)
		}
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// typingPayload reports who is typing by phone number; Google identifies the
// typist by number rather than participant ID.
func typingPayload(t *gmproto.TypingData) string {
	b, _ := json.Marshal(map[string]any{
		"conversationID": t.GetConversationID(),
		"number":         t.GetUser().GetNumber(),
		"typing":         t.GetType() == gmproto.TypingTypes_STARTED_TYPING,
	})
	return string(b)
}

// SetTyping tells the conversation this user is typing. Google offers no
// "stopped" message from paired clients; the indicator times out on its own.
func (c *Client) SetTyping(chatJSON string) error {
	conv, e := parseChat(chatJSON)
	if e != nil {
		return e
	}
	sim, e := route(conv)
	if e != nil {
		return e
	}
	ctx, cancel := c.requestContext()
	defer cancel()
	if e := c.gm.SetTyping(ctx, conv.GetConversationID(), sim); e != nil {
		return errors.New("Could not send typing status.")
	}
	return nil
}

func reactionAction(action string) (gmproto.SendReactionRequest_Action, error) {
	switch action {
	case "add":
		return gmproto.SendReactionRequest_ADD, nil
	case "remove":
		return gmproto.SendReactionRequest_REMOVE, nil
	case "switch":
		return gmproto.SendReactionRequest_SWITCH, nil
	}
	return 0, errors.New("Unknown reaction action.")
}
