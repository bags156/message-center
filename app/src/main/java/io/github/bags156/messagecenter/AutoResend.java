package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Texts sent from Message Center that the phone then couldn't send (Google Messages' plain
 * "couldn't send", usually no cell signal for a text) go once more by themselves when the signal
 * is back. Only once, only within an hour, only plain texts, and never for reasons a resend can't
 * fix (too large, they can't get RCS, an emergency number), so a real problem can't loop.
 */
final class AutoResend {
    /** How long a sent text is watched, and how long a failed one waits for the signal before giving up. */
    static final long WINDOW_MS = 60 * 60_000L;
    /** Without word that the cell signal is back (mobile data off, say), it goes again after this, while connected. */
    static final long FALLBACK_MS = 10 * 60_000L;
    static final String WATCHING = "watching", PENDING = "pending", DONE = "done";

    private final SharedPreferences prefs;

    AutoResend(Context context) { prefs = context.getSharedPreferences("auto_resend", Context.MODE_PRIVATE); }

    /** For unit tests: kept in the given storage. */
    AutoResend(SharedPreferences prefs) { this.prefs = prefs; }

    /** The failures worth one more try: Google Messages' generic "couldn't send". */
    static boolean retryable(String status) { return "OUTGOING_FAILED_GENERIC".equals(status); }

    /** Google's copy of a text sent from here showed up: watch it for an hour. */
    synchronized void sentHere(String messageID, long now) {
        if (messageID == null || messageID.isEmpty()) return;
        JSONObject all = read();
        if (all.has(messageID)) return;
        try { all.put(messageID, new JSONObject().put("at", now).put("state", WATCHING)); } catch (Exception ignored) {}
        prune(all, now);
        save(all);
    }

    /**
     * A message's copy changed. A watched plain text that failed for a reason worth retrying now
     * waits to go again. True if it just started waiting.
     */
    synchronized boolean failed(JSONObject message, long now) {
        String id = message.optString("messageID");
        JSONObject all = read(), e = all.optJSONObject(id);
        if (e == null || !WATCHING.equals(e.optString("state"))) return false;
        String text = PairedProtocol.text(message);
        if (!retryable(PairedProtocol.status(message)) || !PairedProtocol.media(message).isEmpty() || text.isEmpty()) return false;
        try { e.put("state", PENDING).put("failedAt", now).put("chat", message.optString("conversationID")).put("text", text); } catch (Exception ignored) {}
        save(all);
        return true;
    }

    /** Waiting to go again by itself (for the chat's label). */
    synchronized boolean pending(String messageID) {
        JSONObject e = read().optJSONObject(messageID);
        return e != null && PENDING.equals(e.optString("state")) && System.currentTimeMillis() - e.optLong("failedAt") <= WINDOW_MS;
    }

    /** You sent it again yourself (or deleted it): it won't go again by itself. */
    synchronized void cancel(String messageID) {
        JSONObject all = read(), e = all.optJSONObject(messageID);
        if (e == null) return;
        try { e.put("state", DONE); } catch (Exception ignored) {}
        save(all);
    }

    /**
     * The failed texts to send again now: the cell signal came back after they failed (cellBackAt),
     * or they've waited FALLBACK_MS. Each is marked done as it's handed out, so it only ever goes once.
     * Each comes back with its "id", "chat" and "text".
     */
    synchronized List<JSONObject> due(long now, long cellBackAt) {
        JSONObject all = read(); List<JSONObject> out = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next(); JSONObject e = all.optJSONObject(id);
            if (e == null || !PENDING.equals(e.optString("state"))) continue;
            long failedAt = e.optLong("failedAt");
            if (now - failedAt > WINDOW_MS) continue;
            if (cellBackAt > failedAt || now - failedAt >= FALLBACK_MS) {
                try { e.put("state", DONE); out.add(new JSONObject().put("id", id).put("chat", e.optString("chat")).put("text", e.optString("text"))); } catch (Exception ignored) {}
            }
        }
        prune(all, now);
        save(all);
        return out;
    }

    /** When the next failed text would go again without word from the cell signal, or 0. */
    synchronized long nextFallbackAt() {
        long at = 0; JSONObject all = read();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            JSONObject e = all.optJSONObject(it.next());
            if (e != null && PENDING.equals(e.optString("state"))) { long t = e.optLong("failedAt") + FALLBACK_MS; if (at == 0 || t < at) at = t; }
        }
        return at;
    }

    private static void prune(JSONObject all, long now) {
        List<String> old = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next(); JSONObject e = all.optJSONObject(id);
            long since = e == null ? 0 : Math.max(e.optLong("at"), e.optLong("failedAt"));
            if (e == null || now - since > WINDOW_MS) old.add(id);
        }
        for (String id : old) all.remove(id);
    }

    private JSONObject read() {
        try { return new JSONObject(prefs.getString("items", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private void save(JSONObject all) { prefs.edit().putString("items", all.toString()).apply(); }
}
