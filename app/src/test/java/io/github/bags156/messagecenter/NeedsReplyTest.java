package io.github.bags156.messagecenter;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class NeedsReplyTest {
    private static final long T=1_800_000_000_000L;
    private JSONObject chat()throws Exception{return new JSONObject("{conversationID:'chat',participants:[{ID:{participantID:'me'},isMe:true},{ID:{participantID:'other'}}]}");}
    private JSONObject message(String id,String status,long at,String text)throws Exception{return new JSONObject().put("messageID",id).put("conversationID","chat").put("participantID",status.startsWith("OUTGOING")?"me":"other").put("timestamp",T+at)
        .put("messageStatus",new JSONObject().put("status",status)).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content",text))));}
    @Test public void readingDoesNotRemoveFollowUpButActuallySendingDoes()throws Exception{
        JSONObject read=message("in","INCOMING_DISPLAYED",10000,"Synthetic question");
        assertTrue(NeedsReply.pending(List.of(read),chat(),0,0,T+20000));
        JSONObject failed=message("out","OUTGOING_FAILED_GENERIC",11000,"Synthetic answer");assertTrue(NeedsReply.pending(List.of(read,failed),chat(),0,0,T+20000));
        JSONObject sent=message("sent","OUTGOING_COMPLETE",12000,"Synthetic answer");assertFalse(NeedsReply.pending(List.of(read,failed,sent),chat(),0,0,T+20000));
    }
    @Test public void handledAndSnoozedFollowUpsReturnForNewMessagesOrAfterSnooze()throws Exception{
        JSONObject incoming=message("in","INCOMING_COMPLETE",10000,"Synthetic question");assertFalse(NeedsReply.pending(List.of(incoming),chat(),T+10000,0,T+20000));
        JSONObject newer=message("next","INCOMING_COMPLETE",21000,"Another question");assertTrue(NeedsReply.pending(List.of(incoming,newer),chat(),T+10000,0,T+22000));
        assertFalse(NeedsReply.pending(List.of(incoming),chat(),0,T+30000,T+20000));assertTrue(NeedsReply.pending(List.of(incoming),chat(),0,T+30000,T+30000));
    }
    @Test public void reactionsAndDeletedOrEmptyUpdatesDoNotCreateFollowUps()throws Exception{
        assertFalse(NeedsReply.pending(List.of(message("r","INCOMING_COMPLETE",10000,"Loved “Synthetic text”")),chat(),0,0,T+20000));
        assertFalse(NeedsReply.pending(List.of(message("d","INCOMING_DELETED",10000,"Synthetic text"),message("empty","INCOMING_COMPLETE",11000,"")),chat(),0,0,T+20000));
    }
}
