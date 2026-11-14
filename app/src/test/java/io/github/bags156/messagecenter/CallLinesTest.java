package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class CallLinesTest {
    @Test public void durationsReadNaturally() {
        assertEquals("0 sec", CallLines.duration(0));
        assertEquals("45 sec", CallLines.duration(45));
        assertEquals("4 min", CallLines.duration(240));
        assertEquals("4 min 12 sec", CallLines.duration(252));
        assertEquals("1 hr", CallLines.duration(3600));
        assertEquals("1 hr 3 min", CallLines.duration(3790));
    }

    @Test public void eachKindOfCallSaysWhatHappened() {
        assertEquals("Outgoing call · 4 min 12 sec", CallLines.label(CallLines.OUTGOING, 252, false));
        assertEquals("Outgoing call · no answer", CallLines.label(CallLines.OUTGOING, 0, false));
        assertEquals("Incoming call · 1 min", CallLines.label(CallLines.INCOMING, 60, false));
        assertEquals("Incoming video call · 12 min 5 sec", CallLines.label(CallLines.INCOMING, 725, true));
        assertEquals("Missed call", CallLines.label(CallLines.MISSED, 0, false));
        assertEquals("Declined call", CallLines.label(CallLines.REJECTED, 0, false));
        assertEquals("Voicemail", CallLines.label(CallLines.VOICEMAIL, 30, false));
        assertTrue(CallLines.missed(CallLines.MISSED));
        assertFalse(CallLines.missed(CallLines.OUTGOING));
    }

    @Test public void numbersMatchHoweverTheyreWritten() {
        assertTrue(CallLines.sameNumber("+1 (555) 777-0188", "5557770188"));
        assertTrue(CallLines.sameNumber("+15557770188", "15557770188"));
        assertFalse(CallLines.sameNumber("+15557770188", "+15557770189"));
        assertFalse("too short to tell", CallLines.sameNumber("611", "611"));
        assertFalse(CallLines.sameNumber("", "5557770188"));
    }

    @Test public void aCallIsATimelineItemNothingMistakesForAMessage() {
        JSONObject c = CallLines.item(42, 1_800_000_000_000L, CallLines.OUTGOING, 252, false, "+15557770188");
        assertTrue(CallLines.isCall(c));
        assertEquals("call:42", c.optString("messageID"));
        assertEquals(1_800_000_000_000L, PairedProtocol.time(c));
        assertEquals("Outgoing call · 4 min 12 sec", CallLines.label(c));
        assertFalse(CallLines.isCall(new JSONObject()));
    }
}
