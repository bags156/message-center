package io.github.bags156.messagecenter;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadReceiptQueueTest {
    private static final class Memory implements ReadReceiptQueue.Storage {String value="{}";public String read(){return value;}public void write(String s){value=s;}}
    private JSONObject chat()throws Exception{return new JSONObject("{conversationID:'chat',type:'RCS'}");}
    private JSONObject message(String id,long time)throws Exception{return new JSONObject("{conversationID:'chat',type:4,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Synthetic message'}}]}").put("messageID",id).put("timestamp",time);}
    @Test public void disconnectedReadSurvivesRestartAndUsesTheViewedMessage()throws Exception{
        Memory memory=new Memory();ReadReceiptQueue q=new ReadReceiptQueue(memory);q.viewed(chat(),message("seen",1000),false);
        ReadReceiptQueue restored=new ReadReceiptQueue(memory);
        assertEquals("waiting",ReadReceiptQueue.state(restored.get("chat"),true,false,1));
        assertEquals("seen",restored.next(0,1,id->true).getString("messageID"));
    }
    @Test public void failedReadRetriesAfterBackoff()throws Exception{
        ReadReceiptQueue q=new ReadReceiptQueue(new Memory());q.viewed(chat(),message("a",1000),false);q.failed("chat","a",q.get("chat").optLong("revision"),"Connection lost",100);
        assertNull(q.next(101,1,id->true));assertNotNull(q.next(5100,1,id->true));
        assertEquals("retrying",ReadReceiptQueue.state(q.get("chat"),true,true,1));
    }
    @Test public void completionOfOldRequestCannotClearNewTarget()throws Exception{
        ReadReceiptQueue q=new ReadReceiptQueue(new Memory());q.viewed(chat(),message("a",1000),false);q.viewed(chat(),message("b",2000),false);
        q.accepted("chat","a",1,1234);assertEquals("b",q.next(0,1,id->true).getString("messageID"));
    }
    @Test public void googleMasterSwitchBlocksAndReenablingRearmsReceipt()throws Exception{
        ReadReceiptQueue q=new ReadReceiptQueue(new Memory());q.viewed(chat(),message("a",1000),false);
        assertNull(q.next(0,0,id->true));assertEquals("google_off",ReadReceiptQueue.state(q.get("chat"),true,true,0));
        q.accepted("chat","a",q.get("chat").optLong("revision"),100);assertNull(q.next(0,1,id->true));q.googleEnabled();assertNotNull(q.next(0,1,id->true));
    }
    @Test public void optingOutCancelsUnsentRequest()throws Exception{
        ReadReceiptQueue q=new ReadReceiptQueue(new Memory());q.viewed(chat(),message("a",1000),false);assertNull(q.next(0,1,id->false));assertNull(q.get("chat"));
    }
    @Test public void identicalViewedMessageIsNotResentUnlessExplicitRetry()throws Exception{
        ReadReceiptQueue q=new ReadReceiptQueue(new Memory());q.viewed(chat(),message("a",1000),false);q.accepted("chat","a",q.get("chat").optLong("revision"),10);
        assertFalse(q.viewed(chat(),message("a",1000),false));assertNull(q.next(0,1,id->true));
        assertTrue(q.viewed(chat(),message("a",1000),true));assertNotNull(q.next(0,1,id->true));
    }
    @Test public void eventsAndOutgoingMessagesAreNotReceiptTargets()throws Exception{
        assertFalse(ReadReceiptQueue.eligible(message("a",1).put("messageStatus",new JSONObject("{status:'TOMBSTONE_GROUP_RENAMED_LOCAL'}")),chat()));
        assertFalse(ReadReceiptQueue.eligible(message("a",1).put("messageStatus",new JSONObject("{status:'OUTGOING_COMPLETE'}")),chat()));
    }
    @Test public void oldAcknowledgementCannotSwallowAnExplicitRetry()throws Exception{
        ReadReceiptQueue q=new ReadReceiptQueue(new Memory());q.viewed(chat(),message("a",1),false);long old=q.get("chat").optLong("revision");
        q.viewed(chat(),message("a",1),true);q.accepted("chat","a",old,100);assertNotNull(q.next(0,1,id->true));
    }
    @Test public void smsIsNeverPresentedAsSentReadReceipt()throws Exception{
        ReadReceiptQueue q=new ReadReceiptQueue(new Memory());q.viewed(chat(),message("a",1).put("type",1),false);
        assertEquals("sms",ReadReceiptQueue.state(q.get("chat"),true,true,1));
    }
}
