package io.github.bags156.messagecenter;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Google protocol presentation. IDs come from the protocol, never quote matching. */
final class PairedProtocol {
    static String string(JSONObject o,String key) { return o==null ? "":o.optString(key,""); }
    static long millis(long value) {
        if(value>100_000_000_000_000L) return value/1000;
        if(value>0 && value<100_000_000_000L) return value*1000;
        return value;
    }
    static long time(JSONObject m) { return millis(m.optLong("timestamp",m.optLong("lastMessageTimestamp",0))); }
    static String status(JSONObject m) { return string(m.optJSONObject("messageStatus"),"status"); }
    static boolean outgoing(JSONObject m) {
        JSONObject sender=m.optJSONObject("senderParticipant");
        return (sender!=null && sender.optBoolean("isMe")) || status(m).startsWith("OUTGOING");
    }
    static String body(JSONObject m) {
        JSONArray infos=m.optJSONArray("messageInfo"); List<String> parts=new ArrayList<>();
        if(infos!=null) for(int i=0;i<infos.length();i++) {
            JSONObject part=infos.optJSONObject(i); if(part==null) continue;
            String text=string(part.optJSONObject("messageContent"),"content");
            if(!text.isEmpty()) parts.add(text);
            JSONObject media=part.optJSONObject("mediaContent");
            if(media!=null) {
                String name=string(media,"mediaName");
                parts.add(name.isEmpty() ? "[Attachment — open in Google Messages]":"[Attachment: "+name+"]");
            }
        }
        return String.join("\n",parts);
    }
    static String name(JSONObject p) {
        if(p==null) return "";
        if(p.optBoolean("isMe")) return "You";
        for(String key:new String[]{"fullName","firstName","formattedNumber"}) {
            String value=string(p,key); if(!value.isEmpty()) return value;
        }
        return string(p.optJSONObject("ID"),"number");
    }
    static String person(JSONObject chat,String id,JSONObject supplied) {
        String direct=name(supplied); if(!direct.isEmpty()) return direct;
        JSONArray participants=chat==null ? null:chat.optJSONArray("participants");
        if(participants!=null) for(int i=0;i<participants.length();i++) {
            JSONObject p=participants.optJSONObject(i);
            if(p!=null && id.equals(string(p.optJSONObject("ID"),"participantID"))) {
                String n=name(p); if(!n.isEmpty()) return n;
            }
        }
        return id.isEmpty() ? "Unknown participant":"Participant "+id;
    }
    static String title(JSONObject chat) {
        String n=string(chat,"name"); if(!n.isEmpty()) return n;
        JSONArray ps=chat.optJSONArray("participants"); List<String> names=new ArrayList<>();
        if(ps!=null) for(int i=0;i<ps.length();i++) {
            JSONObject p=ps.optJSONObject(i); if(p==null || p.optBoolean("isMe")) continue;
            String name=name(p); if(!name.isEmpty()) names.add(name);
        }
        return names.isEmpty() ? "Conversation "+string(chat,"conversationID"):String.join(", ",names);
    }
    static Map<String,List<String>> reactions(JSONObject m,JSONObject chat) {
        Map<String,List<String>> result=new LinkedHashMap<>(); JSONArray entries=m.optJSONArray("reactions");
        if(entries!=null) for(int i=0;i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i);if(e==null) continue;
            JSONObject data=e.optJSONObject("data");String emoji=string(data,"unicode");
            if(emoji.isEmpty()) emoji=switch(string(data,"type")) {
                case "LOVE","RED_HEART" -> "❤️"; case "LIKE" -> "👍"; case "DISLIKE" -> "👎";
                case "LAUGH" -> "😂";case "SURPRISED" -> "😮";case "SAD","CRYING_FACE" -> "😢";
                case "ANGRY","POUTING_FACE" -> "😡";case "QUESTIONING" -> "❓";default -> "";
            };
            if(emoji.isEmpty()) continue;
            JSONArray actors=e.optJSONArray("participantIDs"); if(actors==null) continue;
            List<String> names=result.computeIfAbsent(emoji,k->new ArrayList<>());
            for(int j=0;j<actors.length();j++) names.add(person(chat,actors.optString(j),null));
        }
        return result;
    }
    static String cursor(JSONObject response) {
        JSONObject c=response.optJSONObject("cursor");
        return c==null || (string(c,"lastItemID").isEmpty() && c.optLong("lastItemTimestamp")==0) ? "":c.toString();
    }
    static boolean notifyLive(boolean old,boolean existed,JSONObject m) {
        return !old && !existed && !outgoing(m) && !body(m).isEmpty() && !status(m).equals("INCOMING_DISPLAYED");
    }
}
