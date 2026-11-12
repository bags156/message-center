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

    @Test public void stopsWaitingForACopyThatNeverCame() throws Exception {
        JSONObject sent = queued(Outbox.SENT, "On my way", "tmp-1", TAP);
        assertEquals(0, OutboxEcho.toDraw(Collections.singletonList(sent), Collections.emptyList(), TAP + Outbox.SENT_GRACE_MS + 1).size());
    }
}
