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
 * back. A send whose outcome is unknown is never retried automatically (that could send it
 * twice); it is marked "unsure" and waits for the person to retry or delete it.
 */
final class Outbox {
    static final String WAITING = "waiting", SENDING = "sending", UNSURE = "unsure", FAILED = "failed";
    /** Held for a few seconds so it can be taken back ("Undo send"); sent once "due" passes. */
    static final String HOLD = "hold";
    private final SharedPreferences prefs;

    Outbox(Context context) { prefs = context.getSharedPreferences("outbox", Context.MODE_PRIVATE); }

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
        JSONObject o = new JSONObject(); long now = System.currentTimeMillis();
        try { o.put("id", UUID.randomUUID().toString()).put("chat", chat).put("text", text).put("reply", reply).put("created", now).put("state", holdMillis > 0 ? HOLD : WAITING).put("due", now + holdMillis); }
        catch (Exception ignored) {}
        List<JSONObject> list = all(); list.add(o); save(list);
        return o;
    }

    synchronized void setState(String id, String state, String error) {
        List<JSONObject> list = all();
        for (JSONObject o : list) if (id.equals(o.optString("id"))) { try { o.put("state", state).put("error", error == null ? "" : error); } catch (Exception ignored) {} }
        save(list);
    }

    synchronized JSONObject get(String id) { for (JSONObject o : all()) if (id.equals(o.optString("id"))) return o; return null; }

    synchronized void remove(String id) {
        List<JSONObject> list = all(); list.removeIf(o -> id.equals(o.optString("id"))); save(list);
    }

    /** The next message to send automatically, or null. Unsure and failed items wait for the person. */
    synchronized JSONObject next() {
        long now = System.currentTimeMillis();
        for (JSONObject o : all()) { String state = o.optString("state"); if (WAITING.equals(state) || (HOLD.equals(state) && o.optLong("due") <= now)) return o; }
        return null;
    }

    /** After a restart, anything that was mid-send has an unknown outcome. */
    synchronized void recover() {
        List<JSONObject> list = all(); boolean changed = false;
        for (JSONObject o : list) if (SENDING.equals(o.optString("state"))) { try { o.put("state", UNSURE); changed = true; } catch (Exception ignored) {} }
        if (changed) save(list);
    }

    private void save(List<JSONObject> list) {
        JSONArray a = new JSONArray(); for (JSONObject o : list) a.put(o);
        prefs.edit().putString("items", a.toString()).apply();
    }
}
