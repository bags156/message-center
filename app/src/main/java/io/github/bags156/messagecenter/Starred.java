package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Messages you starred, kept in this app only (Google's protocol has no starring). A copy of
 * each message is kept, so it stays in the list even if older history isn't loaded.
 */
final class Starred {
    private final SharedPreferences prefs;

    Starred(Context context) { prefs = context.getSharedPreferences("starred", Context.MODE_PRIVATE); }

    boolean has(String messageID) { return !messageID.isEmpty() && prefs.contains(messageID); }

    void toggle(JSONObject message) {
        String id = message.optString("messageID");
        if (id.isEmpty()) return;
        if (has(id)) { prefs.edit().remove(id).apply(); return; }
        JSONObject entry = new JSONObject();
        try { entry.put("message", message).put("starred", System.currentTimeMillis()); } catch (Exception ignored) {}
        prefs.edit().putString(id, entry.toString()).apply();
    }

    /** Starred messages, most recently starred first. */
    List<JSONObject> all() {
        List<JSONObject> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            try { out.add(new JSONObject(String.valueOf(e.getValue()))); } catch (Exception ignored) {}
        }
        out.sort((a, b) -> Long.compare(b.optLong("starred"), a.optLong("starred")));
        List<JSONObject> messages = new ArrayList<>();
        for (JSONObject o : out) { JSONObject m = o.optJSONObject("message"); if (m != null) messages.add(m); }
        return messages;
    }
}
