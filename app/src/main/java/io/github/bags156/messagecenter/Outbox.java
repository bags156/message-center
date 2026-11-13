package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Text messages written while the connection was down. They are sent in order once it is
 * back. A send that couldn't reach Google at all goes again by itself (it can't have been sent).
 * A send whose outcome is unknown is never retried automatically (that could send it twice);
 * it is marked "unsure" and waits for Google's copy, or for the person to retry or delete it.
 */
final class Outbox {
    static final String WAITING = "waiting", SENDING = "sending", UNSURE = "unsure", FAILED = "failed";
    /** Held for a few seconds so it can be taken back ("Undo send"); sent once "due" passes. */
    static final String HOLD = "hold";
    /** Google accepted it; still drawn as sending until Google's copy shows up in the chat (see OutboxEcho). */
    static final String SENT = "sent";
    /** How long a sent text stands in for Google's copy if the copy never shows up. */
    static final long SENT_GRACE_MS = 2 * 60_000;
    /** Tries that couldn't reach Google go again by themselves this many times, then wait for you. */
    static final int MAX_TRIES = 8;
    private final SharedPreferences prefs;

    /** How long a send can go unconfirmed while connected before it's offered to send again. */
    static final long CONFIRM_WAIT_MS = 10 * 60_000L;

    /** What a message held for the connection is waiting for, from the connection line (PairedService.status). */
    static String waitingFor(String status) {
        String s = status == null ? "" : status;
        if (s.startsWith("Weak connection")) return "Waiting for signal • sends automatically";
        if (s.startsWith("Waiting for Google Messages")) return "Waiting for Google Messages • sends automatically";
        return "Waiting to reconnect • sends automatically";
    }

    /** The label for a text that's sending or waiting (not yet in the chat as Google's copy). */
    static String label(JSONObject o, boolean online, String status, long now) {
        switch (o.optString("state")) {
            case HOLD: return "Sending… • Tap to undo";
            case SENDING: case SENT: return o.optString("replaces").isEmpty() ? "Sending…" : "Sending again…";
            case UNSURE: return online && o.optLong("stateAt") > 0 && now - o.optLong("stateAt") > CONFIRM_WAIT_MS ? "Not confirmed • Tap to send again" : "Sent to Google • waiting to confirm delivery";
            case FAILED: return "Not sent • Tap to retry";
            default: return o.optInt("tries") > 0 ? "Waiting for signal • sends automatically" : online ? "Sending…" : waitingFor(status);
        }
    }

    /** How long to wait before going again after the nth try (1-based) couldn't reach Google. */
    static long retryDelay(int tries) {
        long[] steps = {10_000, 30_000, 60_000, 120_000, 300_000};
        return steps[Math.min(Math.max(tries, 1) - 1, steps.length - 1)];
    }

    Outbox(Context context) { prefs = context.getSharedPreferences("outbox", Context.MODE_PRIVATE); }

    /** For unit tests: kept in the given storage. */
    Outbox(SharedPreferences prefs) { this.prefs = prefs; }

    synchronized List<JSONObject> all() {
        List<JSONObject> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs.getString("items", "[]")); for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i)); }
        catch (Exception ignored) {}
        return out;
    }

    synchronized List<JSONObject> forChat(String chat) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : all()) if (chat.equals(o.optString("chat"))) out.add(o);
        return out;
    }

    synchronized JSONObject add(String chat, String text, String reply) { return add(chat, text, reply, 0); }

    /** With holdMillis > 0 the message waits that long (and can be undone) before it is sent. */
    synchronized JSONObject add(String chat, String text, String reply, long holdMillis) {
        return add(chat,text,reply,holdMillis,holdMillis > 0 ? HOLD : WAITING,"");
    }

    private JSONObject add(String chat,String text,String reply,long holdMillis,String state,String error) {
        JSONObject o = new JSONObject(); long now = System.currentTimeMillis();
        try { o.put("id", UUID.randomUUID().toString()).put("chat", chat).put("text", text).put("reply", reply).put("created", now).put("state", state).put("due", now + holdMillis).put("error",error); }
        catch (Exception ignored) {}
        List<JSONObject> list = all(); list.add(o); save(list);
        return o;
    }

    synchronized void setState(String id, String state, String error) {
        List<JSONObject> list = all();
        for (JSONObject o : list) if (id.equals(o.optString("id"))) { try { o.put("state", state).put("error", error == null ? "" : error).put("stateAt", System.currentTimeMillis());
            // Sent again by hand (or put back in line): it goes now, counting its tries afresh.
            if (WAITING.equals(state)) { o.remove("retryAt"); o.remove("tries"); } } catch (Exception ignored) {} }
        save(list);
    }

    /** A text sent again in place of one that failed: once it's accepted, the failed copy is removed (see "replaces"). */
    synchronized JSONObject addReplacing(String chat, String text, String replaces) {
        JSONObject o = add(chat, text, "", 0, WAITING, "");
        List<JSONObject> list = all();
        for (JSONObject x : list) if (x.optString("id").equals(o.optString("id"))) { try { x.put("replaces", replaces); o.put("replaces", replaces); } catch (Exception ignored) {} }
        save(list);
        return o;
    }

    /**
     * The try couldn't reach Google (no or weak connection), so nothing was sent: it waits and goes again
     * by itself, sooner if the connection comes back (retryNow). Returns the wait, or -1 once it has run
     * out of tries and is left for you.
     */
    synchronized long retryLater(String id, String error) {
        long now = System.currentTimeMillis(), delay = -1; List<JSONObject> list = all();
        for (JSONObject o : list) if (id.equals(o.optString("id"))) { int tries = o.optInt("tries") + 1;
            try { o.put("tries", tries).put("error", error == null ? "" : error).put("stateAt", now);
                if (tries >= MAX_TRIES) o.put("state", FAILED).put("error", "Not sent: it couldn't reach Google after several tries.");
                else { delay = retryDelay(tries); o.put("state", WAITING).put("retryAt", now + delay); } }
            catch (Exception ignored) {} }
        save(list);
        return delay;
    }

    /** The connection looks better: what's waiting to go again goes now. True if anything was waiting. */
    synchronized boolean retryNow() {
        boolean any = false; List<JSONObject> list = all();
        for (JSONObject o : list) if (WAITING.equals(o.optString("state")) && o.has("retryAt")) { o.remove("retryAt"); any = true; }
        if (any) save(list);
        return any;
    }

    /** When the next try that's waiting comes up, or 0 if none is waiting that way. */
    synchronized long nextRetryAt() {
        long at = 0; for (JSONObject o : all()) if (WAITING.equals(o.optString("state")) && o.optLong("retryAt") > 0 && (at == 0 || o.optLong("retryAt") < at)) at = o.optLong("retryAt");
        return at;
    }

    /** Whether this message is Google's copy of a text sent from here (matched like arrived does). */
    synchronized boolean copyOfSent(JSONObject message) {
        String tmp = message.optString("tmpID"), mid = message.optString("messageID");
        for (JSONObject o : all()) if (SENT.equals(o.optString("state")) && ((!tmp.isEmpty() && tmp.equals(o.optString("tmpID"))) || (!mid.isEmpty() && mid.equals(o.optString("tmpID"))))) return true;
        return false;
    }

    synchronized JSONObject get(String id) { for (JSONObject o : all()) if (id.equals(o.optString("id"))) return o; return null; }

    synchronized void remove(String id) {
        List<JSONObject> list = all(); list.removeIf(o -> id.equals(o.optString("id"))); save(list);
    }

    /** Google accepted it under this temporary ID: it stays until its copy arrives (arrived) or the grace runs out. */
    synchronized void sent(String id, String tmpID) {
        List<JSONObject> list = all();
        for (JSONObject o : list) if (id.equals(o.optString("id"))) { try { o.put("state", SENT).put("tmpID", tmpID).put("sentAt", System.currentTimeMillis()).put("error", ""); } catch (Exception ignored) {} }
        save(list);
    }

    /** A message arrived in a chat: removes the sent text it's the copy of, and any whose copy never came. True if something changed. */
    synchronized boolean arrived(JSONObject message) {
        String tmp = message.optString("tmpID"), mid = message.optString("messageID"); long now = System.currentTimeMillis();
        List<JSONObject> list = all();
        boolean changed = list.removeIf(o -> SENT.equals(o.optString("state")) && (now - o.optLong("sentAt") > SENT_GRACE_MS
                || (!tmp.isEmpty() && tmp.equals(o.optString("tmpID"))) || (!mid.isEmpty() && mid.equals(o.optString("tmpID")))));
        if (changed) save(list);
        return changed;
    }

    /** The next message to send automatically, or null. Unsure and failed items wait for the person. */
    synchronized JSONObject next() {
        long now = System.currentTimeMillis();
        for (JSONObject o : all()) { String state = o.optString("state"); if ((WAITING.equals(state) && o.optLong("retryAt") <= now) || (HOLD.equals(state) && o.optLong("due") <= now)) return o; }
        return null;
    }

    /** After a restart, anything that was mid-send has an unknown outcome. */
    synchronized void recover() {
        List<JSONObject> list = all(); boolean changed = false;
        for (JSONObject o : list) if (SENDING.equals(o.optString("state"))) { try { o.put("state", UNSURE); changed = true; } catch (Exception ignored) {} }
        long now = System.currentTimeMillis();
        if (list.removeIf(o -> SENT.equals(o.optString("state")) && now - o.optLong("sentAt") > SENT_GRACE_MS)) changed = true;
        if (changed) save(list);
    }

    private void save(List<JSONObject> list) {
        JSONArray a = new JSONArray(); for (JSONObject o : list) a.put(o);
        prefs.edit().putString("items", a.toString()).apply();
    }
}
