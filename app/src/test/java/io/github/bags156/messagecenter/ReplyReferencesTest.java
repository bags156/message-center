package io.github.bags156.messagecenter;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReplyReferencesTest {
    private JSONObject message()throws Exception{return new JSONObject("{messageID:'reply',conversationID:'chat',messageStatus:{status:'INCOMING_COMPLETE'}}");}
    @Test public void deliveryUpdateCannotEraseReplyTarget()throws Exception{
        JSONObject previous=message().put("replyMessage",new JSONObject("{messageID:'original'}"));
        JSONObject updated=ReplyReferences.preserve(previous,message().put("replyMessage",JSONObject.NULL));
        assertEquals("original",ReplyReferences.primary(updated));
    }
    @Test public void replacementReferenceWinsAndDifferentMessagesAreIsolated()throws Exception{
        JSONObject old=message().put("replyMessage",new JSONObject("{messageID:'a'}"));
        assertEquals("b",ReplyReferences.primary(ReplyReferences.preserve(old,message().put("replyMessage",new JSONObject("{messageID:'b'}")))));
        assertEquals("",ReplyReferences.primary(ReplyReferences.preserve(old,message().put("conversationID","other-chat"))));
    }
    @Test public void messageTextDoesNotBecomeAnIdentity()throws Exception{
        JSONObject m=message().put("messageInfo",new org.json.JSONArray("[{messageContent:{content:'Replied to a message: Synthetic reply'}}]"));
        assertTrue(ReplyReferences.isReply(m));assertTrue(ReplyReferences.references(m).isEmpty());
    }
    @Test public void deletedMessageDoesNotRecoverOldQuote()throws Exception{
        JSONObject old=message().put("replyMessage",new JSONObject("{messageID:'a'}"));
        assertEquals("",ReplyReferences.primary(ReplyReferences.preserve(old,message().put("messageStatus",new JSONObject("{status:'INCOMING_DELETED'}")))));
    }
    @Test public void readStatusUpdateRetainsContent()throws Exception{
        JSONObject old=message().put("messageInfo",new org.json.JSONArray("[{messageContent:{content:'Synthetic reply'}}]"));
        JSONObject update=message().put("messageStatus",new JSONObject("{status:'INCOMING_DISPLAYED'}"));
        assertEquals("Synthetic reply",PairedProtocol.body(ReplyReferences.preserve(old,update)));
    }
}
