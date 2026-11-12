package io.github.bags156.messagecenter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Which queued texts to draw in a chat, given the messages already drawn from the chat itself.
 * A text Google has accepted stays drawn as "Sending…" until Google's own copy is in the chat
 * (so the message never disappears for a moment, which made the chat jump up and back down),
 * and is never drawn next to that copy. Plain Java for unit tests.
 */
final class OutboxEcho {
    private OutboxEcho() {}

    /** How much earlier than the tap Google's copy may be stamped and still count as the same text. */
    static final long CLOCK_SLACK_MS = 2000;

    static List<JSONObject> toDraw(List<JSONObject> queued, List<JSONObject> drawn, long now) {
        List<String> ids = new ArrayList<>();
        List<JSONObject> mine = new ArrayList<>();
        for (JSONObject m : drawn) {
            if (!m.optString("tmpID").isEmpty()) ids.add(m.optString("tmpID"));
            if (!m.optString("messageID").isEmpty()) ids.add(m.optString("messageID"));
            if (PairedProtocol.outgoing(m)) mine.add(m);
        }
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : queued) {
            String state = o.optString("state");
            boolean onItsWay = Outbox.SENDING.equals(state) || Outbox.SENT.equals(state);
            if (Outbox.SENT.equals(state) && now - o.optLong("sentAt") > Outbox.SENT_GRACE_MS) continue; // its copy never came; stop waiting
            if (onItsWay && !o.optString("tmpID").isEmpty() && ids.contains(o.optString("tmpID"))) continue;
            // Google's copy can arrive before the send call returns its ID: match the same text sent since the tap (each copy once).
            if (onItsWay) {
                JSONObject copy = null;
                for (JSONObject m : mine)
                    if (PairedProtocol.text(m).equals(o.optString("text")) && PairedProtocol.time(m) >= o.optLong("created") - CLOCK_SLACK_MS) { copy = m; break; }
                if (copy != null) { mine.remove(copy); continue; }
            }
            out.add(o);
        }
        return out;
    }
}
