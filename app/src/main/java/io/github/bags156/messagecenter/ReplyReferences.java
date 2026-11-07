package io.github.bags156.messagecenter;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Protocol identities only: never infers an original from text, position, or sender. */
final class ReplyReferences {
    static String primary(JSONObject m){return PairedProtocol.string(m.optJSONObject("replyMessage"),"messageID").trim();}
    static List<String> references(JSONObject m){List<String> out=new ArrayList<>();add(out,primary(m));
        JSONArray ids=m.optJSONArray("_replyReferenceIDs");if(ids!=null)for(int i=0;i<ids.length();i++)add(out,ids.optString(i));return out;}
    static List<String> identities(JSONObject m){List<String> out=new ArrayList<>();add(out,m.optString("messageID"));
        JSONObject other=m.optJSONObject("anotherMessageID");if(other!=null){add(out,other.optString("messageID"));add(out,other.optString("anotherID"));}
        add(out,m.optString("tmpID"));return out;}
    private static void add(List<String> out,String value){if(value!=null && !value.isEmpty() && !"null".equals(value) && !out.contains(value))out.add(value);}
    /** Read/delivery updates can omit a reply's immutable reference. Preserve a known reference for the same message. */
    static JSONObject preserve(JSONObject previous,JSONObject incoming){
        if(previous==null || !previous.optString("messageID").equals(incoming.optString("messageID"))
            || !previous.optString("conversationID").equals(incoming.optString("conversationID")) || PairedProtocol.status(incoming).contains("DELETED"))return incoming;
        try{
            String status=PairedProtocol.status(incoming);JSONArray info=incoming.optJSONArray("messageInfo");
            if((info==null||info.length()==0)&&(status.equals("INCOMING_DISPLAYED")||status.equals("INCOMING_DELIVERED")||status.equals("OUTGOING_DISPLAYED")||status.equals("OUTGOING_DELIVERED"))) {
                JSONArray previousInfo=previous.optJSONArray("messageInfo");if(previousInfo!=null&&previousInfo.length()>0)incoming.put("messageInfo",previousInfo);
                if(incoming.optLong("timestamp")==0&&previous.optLong("timestamp")>0)incoming.put("timestamp",previous.get("timestamp"));
                if(incoming.optLong("type")==0&&previous.optLong("type")>0)incoming.put("type",previous.get("type"));
                if(incoming.optString("participantID").isEmpty()&&!previous.optString("participantID").isEmpty())incoming.put("participantID",previous.get("participantID"));
                if(incoming.optJSONObject("senderParticipant")==null&&previous.optJSONObject("senderParticipant")!=null)incoming.put("senderParticipant",previous.getJSONObject("senderParticipant"));
            }
            if(primary(incoming).isEmpty() && !primary(previous).isEmpty())incoming.put("replyMessage",previous.getJSONObject("replyMessage"));
            if(references(incoming).isEmpty() && !references(previous).isEmpty())incoming.put("_replyReferenceIDs",new JSONArray(references(previous)));
        }catch(Exception ignored){}return incoming;
    }
    static boolean isReply(JSONObject m){return m.optJSONObject("replyMessage")!=null || !references(m).isEmpty() || ChatText.replyFallbackBody(PairedProtocol.text(m))!=null;}
    /** Safe to show/copy for bug reports: counts and field numbers, no message content, names, numbers, or IDs. */
    static String diagnostics(JSONObject m){JSONObject d=m.optJSONObject("_replyMetadata");
        return "Reply reference diagnostics\nApp: "+BuildConfig.VERSION_NAME+"\nMessage type: "+m.optLong("type")
            +"\nStructured original ID: "+(!primary(m).isEmpty())+"\nProtocol reference count: "+references(m).size()
            +"\nText reply notice: "+(ChatText.replyFallbackBody(PairedProtocol.text(m))!=null)
            +"\nReply metadata present: "+(d!=null&&d.optBoolean("present"))
            +"\nUnmapped reply fields: "+(d==null?"not captured":d.optJSONArray("fields"))
            +"\nUnmapped message fields: "+(d==null?"not captured":d.optJSONArray("messageFields"));}
}
