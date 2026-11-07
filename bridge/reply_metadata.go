package bridge

import (
	"encoding/json"
	"sort"
	"unicode/utf8"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"google.golang.org/protobuf/encoding/protowire"
)

// A forward-compatible reply container may carry an RCS/external message ID
// that is not the phone's local messageID. Do not copy arbitrary message text or
// interpret unrelated unknown message fields as a reply.
func enrichReplyJSON(m *gmproto.Message, raw []byte) ([]byte, error) {
	var object map[string]json.RawMessage
	if err := json.Unmarshal(raw, &object); err != nil {
		return nil, err
	}
	refs := map[string]bool{}
	fields := map[int]bool{}
	messageFields := map[int]bool{}
	scanReplyWire(m.ProtoReflect().GetUnknown(), 0, nil, messageFields)
	reply := m.GetReplyMessage()
	if reply != nil {
		if id := reply.GetMessageID(); id != "" {
			refs[id] = true
		}
		scanReplyWire(reply.ProtoReflect().GetUnknown(), 0, refs, fields)
		if data := reply.GetReplyMessageData(); data != nil {
			scanReplyWire(data.ProtoReflect().GetUnknown(), 0, refs, fields)
		}
	}
	ids := make([]string, 0, len(refs))
	for id := range refs {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	object["_replyReferenceIDs"], _ = json.Marshal(ids)
	object["_replyMetadata"], _ = json.Marshal(map[string]any{"present": reply != nil, "fields": sortedFields(fields), "messageFields": sortedFields(messageFields)})
	return json.Marshal(object)
}
func sortedFields(fields map[int]bool) []int {
	out := make([]int, 0, len(fields))
	for n := range fields {
		out = append(out, n)
	}
	sort.Ints(out)
	return out
}
func referenceToken(value []byte) bool {
	if len(value) < 12 || len(value) > 256 || !utf8.Valid(value) {
		return false
	}
	for _, b := range value {
		if !(b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || b == '-' || b == '_' || b == ':' || b == '.' || b == '/' || b == '@' || b == '+' || b == '=') {
			return false
		}
	}
	return true
}
func scanReplyWire(raw []byte, depth int, refs map[string]bool, fields map[int]bool) {
	if depth > 6 || len(raw) > 64*1024 {
		return
	}
	for count := 0; len(raw) > 0 && count < 256; count++ {
		num, kind, n := protowire.ConsumeTag(raw)
		if n < 0 {
			return
		}
		raw = raw[n:]
		if depth == 0 && fields != nil {
			fields[int(num)] = true
		}
		if kind == protowire.BytesType {
			value, n := protowire.ConsumeBytes(raw)
			if n < 0 {
				return
			}
			raw = raw[n:]
			if refs != nil && referenceToken(value) {
				refs[string(value)] = true
			}
			if refs != nil {
				scanReplyWire(value, depth+1, refs, nil)
			}
		} else {
			n = protowire.ConsumeFieldValue(num, kind, raw)
			if n < 0 {
				return
			}
			raw = raw[n:]
		}
	}
}
