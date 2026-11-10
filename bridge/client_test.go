package bridge

import (
	"encoding/json"
	"errors"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"os"
	"path/filepath"
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
func TestMediaKeyAcceptsJSONBase64(t *testing.T) {
	for _, raw := range []string{"AAECAwQ=", "AAECAwQ", "-_8="} {
		if k, err := mediaKey(raw); err != nil || len(k) == 0 {
			t.Fatalf("%q: %v", raw, err)
		}
	}
	if _, err := mediaKey(""); err == nil {
		t.Fatal("empty key accepted")
	}
}

func TestPhotoAndTextUseIndependentSubmissions(t *testing.T) {
	media := &gmproto.MediaContent{MediaID: "m1", MimeType: "image/jpeg"}
	for _, caption := range []string{"", "  ", "Synthetic caption\nwith another line"} {
		t.Run(caption, func(t *testing.T) {
			calls := 0
			result, err := sendMediaParts(media, caption, "original-id", func(reply string, parts []*gmproto.MessageInfo) (string, error) {
				calls++
				if reply != "original-id" || len(parts) != 1 {
					t.Fatalf("reply changed or a mixed request was sent: %v", parts)
				}
				if calls == 1 {
					if parts[0].GetMediaContent().GetMediaID() != "m1" {
						t.Fatal("photo was not submitted first")
					}
					return "photo-tmp", nil
				}
				if parts[0].GetMessageContent().GetContent() != caption {
					t.Fatal("caption changed")
				}
				return "text-tmp", nil
			})
			want := 1
			if strings.TrimSpace(caption) != "" {
				want = 2
			}
			if err != nil || calls != want || result.MediaTmpID != "photo-tmp" || result.CaptionError != "" {
				t.Fatalf("unexpected submission: %+v %v (%d calls)", result, err, calls)
			}
			if want == 2 && result.CaptionTmpID != "text-tmp" {
				t.Fatal("text transaction identity missing")
			}
		})
	}
}

func TestMediaFailureDoesNotSubmitTextOrRetryPhoto(t *testing.T) {
	calls := 0
	_, err := sendMediaParts(&gmproto.MediaContent{}, "Synthetic caption", "", func(string, []*gmproto.MessageInfo) (string, error) { calls++; return "", errSendOutcomeUnknown })
	if !errors.Is(err, errSendOutcomeUnknown) || calls != 1 {
		t.Fatalf("ambiguous photo was retried or text sent: %v, %d calls", err, calls)
	}
}

func TestCaptionFailureRetainsAcceptedPhotoAndIsNeverRetried(t *testing.T) {
	for _, failure := range []error{errors.New("Google did not accept this message"), errSendOutcomeUnknown} {
		calls := 0
		result, err := sendMediaParts(&gmproto.MediaContent{}, "Synthetic caption", "", func(_ string, parts []*gmproto.MessageInfo) (string, error) {
			calls++
			if calls == 1 {
				return "accepted-photo", nil
			}
			if len(parts) != 1 || parts[0].GetMessageContent() == nil {
				t.Fatal("retry contains photo")
			}
			return "", failure
		})
		if err != nil || calls != 2 || result.MediaTmpID != "accepted-photo" || result.CaptionError != failure.Error() || result.CaptionUncertain != errors.Is(failure, errSendOutcomeUnknown) {
			t.Fatalf("partial acceptance was lost: %+v %v (%d calls)", result, err, calls)
		}
		raw, _ := json.Marshal(result)
		var android map[string]any
		if json.Unmarshal(raw, &android) != nil || android["mediaTmpID"] != "accepted-photo" || android["captionError"] != failure.Error() {
			t.Fatalf("Android cannot recover partial result: %s", raw)
		}
	}
}

func TestSaveStreamWritesAtomicallyAndEnforcesLimit(t *testing.T) {
	dir := t.TempDir()
	ok := filepath.Join(dir, "ok.bin")
	if err := saveStream(strings.NewReader("hello"), ok, 10); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(ok); string(b) != "hello" {
		t.Fatalf("saved %q", b)
	}
	big := filepath.Join(dir, "big.bin")
	if err := saveStream(strings.NewReader("hello world"), big, 5); err == nil {
		t.Fatal("over-limit download accepted")
	}
	if _, err := os.Stat(big); !os.IsNotExist(err) {
		t.Fatal("partial file left behind")
	}
	if _, err := os.Stat(big + ".part"); !os.IsNotExist(err) {
		t.Fatal("temporary file left behind")
	}
	if err := saveStream(strings.NewReader(""), filepath.Join(dir, "empty.bin"), 5); err == nil {
		t.Fatal("empty download accepted")
	}
}
func TestConversationRequestsUseGoogleEnums(t *testing.T) {
	if r := archiveRequest("c1", true); r.GetUpdateData().GetStatus() != gmproto.ConversationStatus_ARCHIVED || r.GetConversationID() != "c1" {
		t.Fatalf("archive: %v", r)
	}
	if r := archiveRequest("c1", false); r.GetUpdateData().GetStatus() != gmproto.ConversationStatus_ACTIVE {
		t.Fatalf("unarchive: %v", r)
	}
	if r := muteRequest("c1", true); r.GetUpdateData().GetMute() != gmproto.ConversationMuteStatus_MUTE {
		t.Fatalf("mute: %v", r)
	}
	conv := &gmproto.Conversation{ConversationID: "c1", Participants: []*gmproto.Participant{
		{ID: &gmproto.SmallInfo{Number: "+15550000000"}, IsMe: true, IsVisible: true},
		{ID: &gmproto.SmallInfo{Number: "+15550102000"}, IsVisible: true}}}
	r, err := blockRequest(conv, "report")
	if err != nil || r.GetAction() != gmproto.ConversationActionStatus_BLOCK_AND_REPORT || r.GetDeleteData().GetPhone() != "+15550102000" || !r.GetAction5().GetField2() {
		t.Fatalf("report: %v %v", r, err)
	}
	if r, _ := blockRequest(conv, "unblock"); r.GetAction() != gmproto.ConversationActionStatus_UNBLOCK || r.GetAction5() != nil {
		t.Fatalf("unblock: %v", r)
	}
	if _, err := blockRequest(conv, "ban"); err == nil {
		t.Fatal("unknown block action accepted")
	}
	group := &gmproto.Conversation{IsGroupChat: true, Participants: conv.Participants}
	if otherPhone(group) != "" {
		t.Fatal("group chats have no single phone")
	}
}

func TestNumbersMustBeInternational(t *testing.T) {
	for _, ok := range []string{"+15550102000", "+447700900123"} {
		if err := validNumber(ok); err != nil {
			t.Fatalf("%s rejected: %v", ok, err)
		}
	}
	for _, bad := range []string{"5550102000", "+1 555", "+1555-010", ""} {
		if validNumber(bad) == nil {
			t.Fatalf("%q accepted", bad)
		}
	}
}
func TestLatestCountStaysInRange(t *testing.T) {
	for in, want := range map[int]int64{-3: 1, 0: 1, 8: 8, 50: 50, 500: 50} {
		if got := latestCount(in); got != want {
			t.Fatalf("latestCount(%d) = %d, want %d", in, got, want)
		}
	}
}
