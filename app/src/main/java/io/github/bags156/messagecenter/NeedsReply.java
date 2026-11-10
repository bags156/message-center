package io.github.bags156.messagecenter;

import org.json.JSONObject;
import java.util.List;

/** A follow-up list from synced content, independent of read/unread and read receipts. */
final class NeedsReply {
    static boolean meaningful(JSONObject m){String status=PairedProtocol.status(m),body=PairedProtocol.body(m);
        if(body.isEmpty()||status.contains("TOMBSTONE")||status.contains("DELETED"))return false;
        String text=PairedProtocol.text(m);return ReactionParser.parse(text)==null&&ChatText.reactionLine("",text)==null;
    }
    static boolean sent(JSONObject m){String s=PairedProtocol.status(m);return s.equals("OUTGOING_COMPLETE")||s.equals("OUTGOING_DELIVERED")||s.equals("OUTGOING_DISPLAYED");}
    static long incoming(List<JSONObject> messages,JSONObject chat){long at=0;for(JSONObject m:messages)if(meaningful(m)&&!PairedProtocol.outgoing(m,chat))at=Math.max(at,PairedProtocol.time(m));return at;}
    static boolean pending(List<JSONObject> messages,JSONObject chat,long dismissed,long snoozedUntil,long now){
        if(snoozedUntil>now)return false;long incoming=incoming(messages,chat),answered=0;
        for(JSONObject m:messages)if(meaningful(m)&&PairedProtocol.outgoing(m,chat)&&sent(m))answered=Math.max(answered,PairedProtocol.time(m));
        return incoming>0&&incoming>dismissed&&incoming>answered;
    }
    private NeedsReply(){}
}
