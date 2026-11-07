package io.github.bags156.messagecenter;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PairedProtocolTest {
    private JSONObject json(String s)throws Exception{return new JSONObject(s);}
    @Test public void reactionUsesProtocolActorAndClearsWhenArrayEmpty()throws Exception {
        JSONObject chat=json("{participants:[{ID:{participantID:'p2'},fullName:'Alex'}]}");
        JSONObject m=json("{reactions:[{data:{unicode:'❤️'},participantIDs:['p2']}]}");
        assertEquals("Alex",PairedProtocol.reactions(m,chat).get("❤️").get(0));
        m.put("reactions",new org.json.JSONArray());assertTrue(PairedProtocol.reactions(m,chat).isEmpty());
    }
    @Test public void enumReactionHasEmojiWhenUnicodeMissing()throws Exception {
        assertTrue(PairedProtocol.reactions(json("{reactions:[{data:{type:'RED_HEART'},participantIDs:['p9']}]}") ,new JSONObject()).containsKey("❤️"));
        assertTrue(PairedProtocol.reactions(json("{reactions:[{data:{type:'LOVE'},participantIDs:['p9']}]}") ,new JSONObject()).containsKey("😍"));
    }
    @Test public void missingActorIsNotInvented()throws Exception {
        assertEquals("Participant p9",PairedProtocol.person(new JSONObject(),"p9",null));
    }
    @Test public void timestampUnitsAreNormalized(){assertEquals(1794000000000L,PairedProtocol.millis(1794000000000000L));assertEquals(1794000000000L,PairedProtocol.millis(1794000000000L));}
    @Test public void statusIdentifiesOutgoingWithoutSender()throws Exception {
        assertTrue(PairedProtocol.outgoing(json("{messageStatus:{status:'OUTGOING_DELIVERED'}}")));
    }
    @Test public void historyAndUpdatesDoNotCreateNewMessageAlerts()throws Exception {
        JSONObject m=json("{messageInfo:[{messageContent:{content:'Hello'}}],messageStatus:{status:'INCOMING_COMPLETE'}}");
        assertTrue(PairedProtocol.notifyLive(false,false,m));assertFalse(PairedProtocol.notifyLive(true,false,m));assertFalse(PairedProtocol.notifyLive(false,true,m));
        m.getJSONObject("messageStatus").put("status","OUTGOING_COMPLETE");assertFalse(PairedProtocol.notifyLive(false,false,m));
    }
    @Test public void replyIsNotRemovedFromText()throws Exception {
        assertEquals("Actual reply",PairedProtocol.body(json("{messageInfo:[{messageContent:{content:'Actual reply'}}],replyMessage:{messageID:'m1'}}")));
    }
    @Test public void emptyCursorDoesNotLoop()throws Exception {assertEquals("",PairedProtocol.cursor(json("{cursor:{lastItemID:'',lastItemTimestamp:'0'}}")));assertFalse(PairedProtocol.cursor(json("{cursor:{lastItemID:'m1',lastItemTimestamp:'12'}}")).isEmpty());}
}
