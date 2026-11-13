package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Sends that never reached Google go again by themselves; texts your phone couldn't send go again once. */
public class SendRetryTest {
    @Test public void aSendThatNeverLeftWaitsThenGoesAgain() {
        Outbox box = new Outbox(new MemoryPrefs());
        String id = box.add("c1", "hi", "").optString("id");
        assertNotNull(box.next());
        long wait = box.retryLater(id, "Not sent: couldn't reach Google.");
        assertEquals(10_000, wait);
        assertNull("it waits out its delay", box.next());
        assertTrue(box.nextRetryAt() > System.currentTimeMillis());
        assertEquals(Outbox.WAITING, box.get(id).optString("state"));
        assertEquals(1, box.get(id).optInt("tries"));
        assertTrue("the connection came back", box.retryNow());
        assertNotNull("so it goes now", box.next());
        assertEquals(0, box.nextRetryAt());
    }

    @Test public void triesGetFurtherApartThenWaitForYou() {
        assertEquals(10_000, Outbox.retryDelay(1));
        assertEquals(30_000, Outbox.retryDelay(2));
        assertEquals(300_000, Outbox.retryDelay(7));
        Outbox box = new Outbox(new MemoryPrefs());
        String id = box.add("c1", "hi", "").optString("id");
        for (int i = 1; i < Outbox.MAX_TRIES; i++) assertTrue(box.retryLater(id, "Not sent") > 0);
        assertEquals(-1, box.retryLater(id, "Not sent"));
        assertEquals(Outbox.FAILED, box.get(id).optString("state"));
        box.setState(id, Outbox.WAITING, "");
        assertEquals("sent again by hand: tries start over", 0, box.get(id).optInt("tries"));
        assertNotNull(box.next());
    }

    @Test public void aResendRemembersWhatItReplaces() {
        Outbox box = new Outbox(new MemoryPrefs());
        JSONObject o = box.addReplacing("c1", "hi", "failed-1");
        assertEquals("failed-1", box.get(o.optString("id")).optString("replaces"));
    }

    @Test public void googlesCopyOfATextSentHereIsRecognized() throws Exception {
        Outbox box = new Outbox(new MemoryPrefs());
        String id = box.add("c1", "hi", "").optString("id");
        box.sent(id, "tmp-1");
        assertTrue(box.copyOfSent(new JSONObject().put("messageID", "m1").put("tmpID", "tmp-1")));
        assertFalse(box.copyOfSent(new JSONObject().put("messageID", "m2").put("tmpID", "tmp-2")));
    }

    @Test public void labelsSayWhatItsWaitingFor() throws Exception {
        long now = 100_000_000;
        JSONObject waiting = new JSONObject().put("state", Outbox.WAITING);
        assertEquals("Waiting for signal • sends automatically", Outbox.label(waiting, false, "Weak connection • reconnecting on its own", now));
        assertEquals("Waiting for Google Messages • sends automatically", Outbox.label(waiting, false, "Waiting for Google Messages on your phone • reconnecting on its own", now));
        assertEquals("Waiting to reconnect • sends automatically", Outbox.label(waiting, false, "Connection ended • reconnecting on its own", now));
        assertEquals("a try that couldn't get out", "Waiting for signal • sends automatically", Outbox.label(new JSONObject().put("state", Outbox.WAITING).put("tries", 2), true, "", now));
        JSONObject unsure = new JSONObject().put("state", Outbox.UNSURE).put("stateAt", now - 60_000);
        assertEquals("Sent to Google • waiting to confirm delivery", Outbox.label(unsure, true, "", now));
        unsure.put("stateAt", now - Outbox.CONFIRM_WAIT_MS - 1);
        assertEquals("Not confirmed • Tap to send again", Outbox.label(unsure, true, "", now));
        assertEquals("not while disconnected", "Sent to Google • waiting to confirm delivery", Outbox.label(unsure, false, "", now));
        assertEquals("Sending again…", Outbox.label(new JSONObject().put("state", Outbox.SENDING).put("replaces", "m1"), true, "", now));
    }

    @Test public void aPhotoThatCouldntBeUploadedWaitsThenGoesAgain() throws Exception {
        String[] saved = {"[]"};
        MediaOutbox box = new MediaOutbox(new MediaOutbox.Storage() { public String read() { return saved[0]; } public void write(String data) { saved[0] = data; } });
        JSONObject job = new JSONObject().put("id", "j1").put("chat", "c1").put("state", MediaOutbox.WAITING).put("attempt", 0);
        box.record(job);
        JSONObject begun = box.begin("j1", false);
        box.failed("j1", begun.optLong("attempt"), "Not sent: the attachment couldn't be uploaded.", false);
        JSONObject after = box.get("j1");
        assertEquals(MediaOutbox.WAITING, after.optString("state"));
        assertEquals(1, after.optInt("tries"));
        assertNull("it waits out its delay", box.next(System.currentTimeMillis()));
        assertEquals("Waiting for signal • sends automatically", MediaOutbox.label(after, true, ""));
        assertTrue(box.retryNow());
        assertNotNull("the connection came back", box.next(System.currentTimeMillis()));
        box.begin("j1", false);
        box.failed("j1", box.get("j1").optLong("attempt"), "Send outcome unknown. Check the conversation.", false);
        assertEquals("never sent twice on a guess", MediaOutbox.UNSURE, box.get("j1").optString("state"));
        assertEquals("Sent to Google • waiting to confirm delivery", MediaOutbox.label(box.get("j1"), true, ""));
    }

    private static JSONObject copy(String id, String status, String text) throws Exception {
        return new JSONObject().put("messageID", id).put("conversationID", "c1").put("participantID", "me")
            .put("messageStatus", new JSONObject().put("status", status))
            .put("messageInfo", new JSONArray().put(new JSONObject().put("messageContent", new JSONObject().put("content", text))));
    }

    @Test public void aTextYourPhoneCouldntSendGoesAgainOnceWhenTheSignalIsBack() throws Exception {
        AutoResend r = new AutoResend(new MemoryPrefs());
        long t = 1_000_000;
        r.sentHere("m1", t);
        assertFalse("not failed yet", r.failed(copy("m1", "OUTGOING_SENDING", "hi"), t));
        assertTrue(r.failed(copy("m1", "OUTGOING_FAILED_GENERIC", "hi"), t + 1000));
        assertTrue("only once", !r.failed(copy("m1", "OUTGOING_FAILED_GENERIC", "hi"), t + 2000));
        assertTrue(r.due(t + 5000, 0).isEmpty());
        List<JSONObject> now = r.due(t + 6000, t + 5500);
        assertEquals("the cell signal came back", 1, now.size());
        assertEquals("hi", now.get(0).optString("text"));
        assertEquals("c1", now.get(0).optString("chat"));
        assertTrue("never twice", r.due(t + AutoResend.FALLBACK_MS * 2, t + 9000).isEmpty());
    }

    @Test public void withoutWordOfTheSignalItWaitsTenMinutes() throws Exception {
        AutoResend r = new AutoResend(new MemoryPrefs());
        long t = 1_000_000;
        r.sentHere("m1", t);
        r.failed(copy("m1", "OUTGOING_FAILED_GENERIC", "hi"), t);
        assertEquals(t + AutoResend.FALLBACK_MS, r.nextFallbackAt());
        assertTrue(r.due(t + AutoResend.FALLBACK_MS - 1, 0).isEmpty());
        assertEquals(1, r.due(t + AutoResend.FALLBACK_MS, 0).size());
    }

    @Test public void notForFailuresAResendCantFixOrMessagesNotSentHere() throws Exception {
        AutoResend r = new AutoResend(new MemoryPrefs());
        r.sentHere("big", 0);
        assertFalse(r.failed(copy("big", "OUTGOING_FAILED_TOO_LARGE", "hi"), 1));
        r.sentHere("rcs", 0);
        assertFalse(r.failed(copy("rcs", "OUTGOING_FAILED_RECIPIENT_LOST_RCS", "hi"), 1));
        assertFalse("sent from the phone itself", r.failed(copy("elsewhere", "OUTGOING_FAILED_GENERIC", "hi"), 1));
        r.sentHere("mine", 0);
        r.failed(copy("mine", "OUTGOING_FAILED_GENERIC", "hi"), 1);
        r.cancel("mine");
        assertTrue("you sent it again yourself", r.due(AutoResend.FALLBACK_MS * 3, 2).isEmpty());
    }
}
