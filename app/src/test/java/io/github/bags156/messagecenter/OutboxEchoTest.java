package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class OutboxEchoTest {
    static final long TAP = 1_794_400_000_000L;

    static JSONObject queued(String state, String text, String tmp, long sentAt) throws Exception {
        return new JSONObject().put("id", "q-" + text).put("state", state).put("text", text).put("created", TAP).put("tmpID", tmp).put("sentAt", sentAt);
    }

    static JSONObject mine(String id, String tmp, String text, long when) throws Exception {
        return new JSONObject().put("messageID", id).put("tmpID", tmp).put("timestamp", when * 1000)
                .put("messageStatus", new JSONObject().put("status", "OUTGOING_DELIVERED"))
                .put("messageInfo", new JSONArray().put(new JSONObject().put("messageContent", new JSONObject().put("content", text))));
    }

    @Test public void acceptedTextStaysUntilItsCopyArrives() throws Exception {
        JSONObject sent = queued(Outbox.SENT, "On my way", "tmp-1", TAP + 500);
        // Google accepted it but its copy isn't in the chat yet: still drawn (this gap made the chat jump).
        assertEquals(1, OutboxEcho.toDraw(Collections.singletonList(sent), Collections.emptyList(), TAP + 1000).size());
        // Its copy is drawn: the queued one isn't drawn next to it.
        assertEquals(0, OutboxEcho.toDraw(Collections.singletonList(sent), Collections.singletonList(mine("m1", "tmp-1", "On my way", TAP + 400)), TAP + 1000).size());
        // Matched by Google's ID too.
        assertEquals(0, OutboxEcho.toDraw(Collections.singletonList(sent), Collections.singletonList(mine("tmp-1", "", "On my way", TAP + 400)), TAP + 1000).size());
    }

    @Test public void copyBeforeTheSendReturnsIsMatchedByText() throws Exception {
        JSONObject sending = queued(Outbox.SENDING, "On my way", "", 0);
        assertEquals(0, OutboxEcho.toDraw(Collections.singletonList(sending), Collections.singletonList(mine("m1", "x", "On my way", TAP + 300)), TAP + 1000).size());
        // The same words sent earlier don't count.
        assertEquals(1, OutboxEcho.toDraw(Collections.singletonList(sending), Collections.singletonList(mine("m0", "y", "On my way", TAP - 60_000)), TAP + 1000).size());
        // One copy hides one queued text, not two.
        JSONObject again = queued(Outbox.SENDING, "On my way", "", 0);
        assertEquals(1, OutboxEcho.toDraw(Arrays.asList(sending, again), Collections.singletonList(mine("m1", "x", "On my way", TAP + 300)), TAP + 1000).size());
    }

    @Test public void waitingAndFailedTextsAlwaysShow() throws Exception {
        List<JSONObject> drawn = Collections.singletonList(mine("m1", "x", "Hi", TAP + 300));
        assertEquals(1, OutboxEcho.toDraw(Collections.singletonList(queued(Outbox.WAITING, "Hi", "", 0)), drawn, TAP + 1000).size());
        assertEquals(1, OutboxEcho.toDraw(Collections.singletonList(queued(Outbox.FAILED, "Hi", "", 0)), drawn, TAP + 1000).size());
        assertEquals(1, OutboxEcho.toDraw(Collections.singletonList(queued(Outbox.HOLD, "Hi", "", 0)), drawn, TAP + 1000).size());
    }

    @Test public void anUnsureTextIsSettledByItsCopy() throws Exception {
        // Google never answered the send; the copy arriving shows it went, so it isn't drawn twice.
        JSONObject unsure = queued(Outbox.UNSURE, "Do you think so?", "", 0);
        assertEquals(0, OutboxEcho.toDraw(Collections.singletonList(unsure), Collections.singletonList(mine("m1", "", "Do you think so?", TAP + 900)), TAP + 60_000).size());
        assertEquals(1, OutboxEcho.toDraw(Collections.singletonList(unsure), Collections.emptyList(), TAP + 60_000).size());
        // And it leaves the outbox when the copy arrives (in its chat, from you, since it was sent).
        Outbox box = new Outbox(new MemoryPrefs());
        JSONObject o = box.add("c1", "Do you think so?", "");
        box.setState(o.optString("id"), Outbox.UNSURE, "Send outcome unknown.");
        long created = box.get(o.optString("id")).optLong("created");
        JSONObject elsewhere = mine("m0", "", "Do you think so?", created + 500).put("conversationID", "c2");
        assertEquals(false, box.arrived(elsewhere));
        JSONObject earlier = mine("m0", "", "Do you think so?", created - 60_000).put("conversationID", "c1");
        assertEquals(false, box.arrived(earlier));
        assertEquals(true, box.unsure("c1"));
        JSONObject copy = mine("m1", "", "Do you think so?", created + 500).put("conversationID", "c1");
        assertEquals(true, box.arrived(copy));
        assertEquals(false, box.unsure("c1"));
    }

    @Test public void anUnsureTextThePhoneNeverGotGoesAgainOnce() throws Exception {
        Outbox box = new Outbox(new MemoryPrefs());
        JSONObject o = box.add("c1", "Are you coming?", "");
        String id = o.optString("id");
        box.setState(id, Outbox.UNSURE, "Send outcome unknown.");
        // The phone's newest messages don't have it: it goes again by itself.
        assertEquals(1, box.notOnPhone("c1"));
        assertEquals(Outbox.WAITING, box.get(id).optString("state"));
        assertEquals(id, box.next().optString("id"));
        // Unsure a second time: it waits for you instead of going a third time.
        box.setState(id, Outbox.UNSURE, "Send outcome unknown.");
        assertEquals(0, box.notOnPhone("c1"));
        assertEquals(Outbox.FAILED, box.get(id).optString("state"));
    }

    @Test public void anOldUnsureTextWaitsForYou() throws Exception {
        MemoryPrefs prefs = new MemoryPrefs();
        Outbox box = new Outbox(prefs);
        JSONObject o = box.add("c1", "Long ago", "");
        String id = o.optString("id");
        box.setState(id, Outbox.UNSURE, "Send outcome unknown.");
        // Made 20 minutes ago (as after an update): not sent again by itself.
        org.json.JSONArray all = new org.json.JSONArray(prefs.getString("items", "[]"));
        for (int i = 0; i < all.length(); i++) all.getJSONObject(i).put("created", System.currentTimeMillis() - 20 * 60_000L);
        prefs.edit().putString("items", all.toString()).apply();
        assertEquals(0, box.notOnPhone("c1"));
        assertEquals(Outbox.FAILED, box.get(id).optString("state"));
    }

    @Test public void stopsWaitingForACopyThatNeverCame() throws Exception {
        JSONObject sent = queued(Outbox.SENT, "On my way", "tmp-1", TAP);
        assertEquals(0, OutboxEcho.toDraw(Collections.singletonList(sent), Collections.emptyList(), TAP + Outbox.SENT_GRACE_MS + 1).size());
    }
}
