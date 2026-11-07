package bridge

import (
	"encoding/json"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"google.golang.org/protobuf/encoding/protowire"
	"testing"
)

func TestUnknownReplyMetadataRetainsOpaqueIdentityInLiveAndHistory(t *testing.T) {
	id := "00000000-1111-2222-3333-444444444444"
	data := &gmproto.ReplyMessageData{}
	wire := protowire.AppendTag(nil, 7, protowire.BytesType)
	wire = protowire.AppendString(wire, id)
	data.ProtoReflect().SetUnknown(wire)
	m := &gmproto.Message{MessageID: "reply", ReplyMessage: &gmproto.ReplyMessage{ReplyMessageData: data}}
	raw, err := marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		IDs []string `json:"_replyReferenceIDs"`
	}
	if err = json.Unmarshal([]byte(raw), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.IDs) != 1 || result.IDs[0] != id {
		t.Fatalf("reply identity lost: %s", raw)
	}
	raw, err = marshal(&gmproto.ListMessagesResponse{Messages: []*gmproto.Message{m}})
	if err != nil {
		t.Fatal(err)
	}
	var history struct {
		Messages []struct {
			IDs []string `json:"_replyReferenceIDs"`
		} `json:"messages"`
	}
	json.Unmarshal([]byte(raw), &history)
	if len(history.Messages) != 1 || len(history.Messages[0].IDs) != 1 {
		t.Fatalf("history identity lost: %s", raw)
	}
}
func TestUnrelatedUnknownMessageFieldsAreNotReplyReferences(t *testing.T) {
	m := &gmproto.Message{MessageID: "m"}
	raw := protowire.AppendTag(nil, 40, protowire.BytesType)
	raw = protowire.AppendString(raw, "00000000-1111-2222-3333-444444444444")
	m.ProtoReflect().SetUnknown(raw)
	encoded, _ := marshal(m)
	var out struct {
		IDs []string `json:"_replyReferenceIDs"`
	}
	json.Unmarshal([]byte(encoded), &out)
	if len(out.IDs) != 0 {
		t.Fatal("unrelated field became reply reference")
	}
}
func TestReplyMetadataDoesNotTreatTextAsReference(t *testing.T) {
	if referenceToken([]byte("Private message text with spaces")) || referenceToken([]byte("actor1")) {
		t.Fatal("text or short actor ID accepted")
	}
}
func TestMalformedReplyWireDoesNotPanic(t *testing.T) {
	scanReplyWire([]byte{0xff}, 0, map[string]bool{}, map[int]bool{})
}
