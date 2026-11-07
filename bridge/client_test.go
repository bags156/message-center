package bridge

import (
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"strings"
	"testing"
)

func TestRouteDoesNotGuessMissingSIM(t *testing.T) {
	if _, err := route(&gmproto.Conversation{DefaultOutgoingID: "me"}); err == nil {
		t.Fatal("missing SIM route accepted")
	}
}
func TestRouteUsesMatchingOwnParticipant(t *testing.T) {
	conv := &gmproto.Conversation{DefaultOutgoingID: "me2", Participants: []*gmproto.Participant{
		{ID: &gmproto.SmallInfo{ParticipantID: "me1"}, IsMe: true, SimPayload: &gmproto.SIMPayload{SIMNumber: 1}},
		{ID: &gmproto.SmallInfo{ParticipantID: "me2"}, IsMe: true, SimPayload: &gmproto.SIMPayload{SIMNumber: 2}},
	}}
	sim, err := route(conv)
	if err != nil || sim.GetSIMNumber() != 2 {
		t.Fatalf("incorrect SIM: %v %v", sim, err)
	}
}
func TestReadOnlyRouteRejected(t *testing.T) {
	if _, err := route(&gmproto.Conversation{ReadOnly: true}); err == nil {
		t.Fatal("read-only chat accepted")
	}
}
func TestInvalidSessionDoesNotEchoCredential(t *testing.T) {
	_, err := NewClient("private-secret-invalid-json", nil)
	if err == nil || strings.Contains(err.Error(), "private-secret") {
		t.Fatal("bad credential handling")
	}
}
func TestEmptyReactionSetIsExplicit(t *testing.T) {
	raw, err := marshal(&gmproto.Message{MessageID: "m1"})
	if err != nil || !strings.Contains(raw, `"reactions":[]`) {
		t.Fatalf("empty reaction state missing: %s %v", raw, err)
	}
}
func TestCursorRejectsInvalidData(t *testing.T) {
	if _, e := parseCursor("not-json"); e == nil {
		t.Fatal("invalid cursor accepted")
	}
	if c, e := parseCursor(""); c != nil || e != nil {
		t.Fatal("empty cursor not accepted")
	}
}
func TestSettingsPayloadOnlyCarriesReceiptSwitch(t *testing.T) {
	if _, ok := settingsPayload(&gmproto.Settings{}); ok {
		t.Fatal("settings without RCS data forwarded")
	}
	raw, ok := settingsPayload(&gmproto.Settings{RCSSettings: &gmproto.RCSSettings{IsEnabled: true, SendReadReceipts: true}})
	if !ok || raw != `{"rcsEnabled":true,"sendReadReceipts":true}` {
		t.Fatalf("unexpected settings payload: %s", raw)
	}
}
func TestThumbnailJSONSkipsEmptyImages(t *testing.T) {
	raw := thumbnailJSON(&gmproto.GetThumbnailResponse{Thumbnail: []*gmproto.GetThumbnailResponse_Thumbnail{
		{Identifier: "p1", Data: &gmproto.ThumbnailData{ImageBuffer: []byte{1, 2, 3}}},
		{Identifier: "p2", Data: &gmproto.ThumbnailData{}},
	}})
	if raw != `{"p1":"AQID"}` {
		t.Fatalf("unexpected thumbnails: %s", raw)
	}
}
func TestSplitIDsDropsBlanks(t *testing.T) {
	if got := splitIDs(" a, ,b,"); len(got) != 2 || got[0] != "a" || got[1] != "b" {
		t.Fatalf("unexpected ids: %v", got)
	}
}
func TestTypingPayloadCarriesNumberAndState(t *testing.T) {
	raw := typingPayload(&gmproto.TypingData{ConversationID: "c1", User: &gmproto.User{Number: "+15550102000"}, Type: gmproto.TypingTypes_STARTED_TYPING})
	if raw != `{"conversationID":"c1","number":"+15550102000","typing":true}` {
		t.Fatalf("unexpected typing payload: %s", raw)
	}
	if raw := typingPayload(&gmproto.TypingData{ConversationID: "c1"}); raw != `{"conversationID":"c1","number":"","typing":false}` {
		t.Fatalf("unexpected stopped payload: %s", raw)
	}
}
func TestReactionActionsMapToGoogle(t *testing.T) {
	for name, want := range map[string]gmproto.SendReactionRequest_Action{"add": gmproto.SendReactionRequest_ADD, "remove": gmproto.SendReactionRequest_REMOVE, "switch": gmproto.SendReactionRequest_SWITCH} {
		if got, err := reactionAction(name); err != nil || got != want {
			t.Fatalf("%s mapped to %v %v", name, got, err)
		}
	}
	if _, err := reactionAction("replace"); err == nil {
		t.Fatal("unknown action accepted")
	}
}
