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

    synchronized JSONObject add(String chat, String text, String reply) {
        JSONObject o = new JSONObject();
        try { o.put("id", UUID.randomUUID().toString()).put("chat", chat).put("text", text).put("reply", reply).put("created", System.currentTimeMillis()).put("state", WAITING); }
        catch (Exception ignored) {}
        List<JSONObject> list = all(); list.add(o); save(list);
        return o;
    }

    synchronized void setState(String id, String state, String error) {
        List<JSONObject> list = all();
        for (JSONObject o : list) if (id.equals(o.optString("id"))) { try { o.put("state", state).put("error", error == null ? "" : error); } catch (Exception ignored) {} }
        save(list);
    }

    synchronized void remove(String id) {
        List<JSONObject> list = all(); list.removeIf(o -> id.equals(o.optString("id"))); save(list);
    }

    /** The next message to send automatically, or null. Unsure and failed items wait for the person. */
    synchronized JSONObject next() {
        for (JSONObject o : all()) if (WAITING.equals(o.optString("state"))) return o;
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
