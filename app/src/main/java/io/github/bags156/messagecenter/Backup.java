package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Everything Message Center keeps that Google Messages doesn't: starred messages, nicknames,
 * reminders, scheduled messages, per-chat settings (themes, pins, receipts, mutes), and app
 * settings. Messages themselves stay in Google Messages; the Google sign-in is never included.
 */
final class Backup {
    private Backup() {}
    static final int VERSION = 1;
    /** Stores copied whole. */
    private static final String[] STORES = {"starred", "nicknames", "reminders", "scheduled", "chat-settings"};
    /** App settings worth carrying over (not connection state or one-time prompts). */
    private static final Set<String> SETTINGS = new HashSet<>(java.util.Arrays.asList(
        "mention_name", "send_typing", "voice_speed", "undo_send", "link_previews", "full_photos", "auto_copy_codes", "theme", "text_scale", "nudge_minutes", "maps_app", "promotions_separate", "quick_replies", "quick_replies_on", "alert_keywords", "autoreply_text"));

    static String export(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "Message Center").put("backupVersion", VERSION).put("appVersion", BuildConfig.VERSION_NAME).put("created", System.currentTimeMillis());
        JSONObject stores = new JSONObject();
        for (String name : STORES) stores.put(name, dump(context.getSharedPreferences(name, Context.MODE_PRIVATE), null));
        stores.put("paired", dump(context.getSharedPreferences("paired", Context.MODE_PRIVATE), SETTINGS));
        root.put("stores", stores);
        return root.toString(2);
    }

    /** Restores a backup over the current data; returns how many entries were restored. */
    static int restore(Context context, String text) throws Exception {
        JSONObject root = new JSONObject(text);
        if (!"Message Center".equals(root.optString("app")) || root.optInt("backupVersion") < 1)
            throw new IllegalArgumentException("This isn't a Message Center backup file.");
        if (root.optInt("backupVersion") > VERSION) throw new IllegalArgumentException("This backup is from a newer version. Update Message Center first.");
        JSONObject stores = root.getJSONObject("stores");
        int count = 0;
        for (String name : STORES) {
            JSONObject values = stores.optJSONObject(name);
            if (values == null) continue;
            SharedPreferences.Editor edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear();
            count += load(edit, values, null);
            edit.commit();
        }
        JSONObject settings = stores.optJSONObject("paired");
        if (settings != null) {
            SharedPreferences.Editor edit = context.getSharedPreferences("paired", Context.MODE_PRIVATE).edit();
            count += load(edit, settings, SETTINGS);
            edit.commit();
        }
        Nicknames.reload(context);
        Scheduled.rearmAll(context);
        Reminders.rearmAll(context);
        return count;
    }

    private static JSONObject dump(SharedPreferences prefs, Set<String> only) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (only != null && !only.contains(e.getKey())) continue;
            Object v = e.getValue(); JSONObject item = new JSONObject();
            if (v instanceof String) item.put("t", "s").put("v", v);
            else if (v instanceof Boolean) item.put("t", "b").put("v", v);
            else if (v instanceof Integer) item.put("t", "i").put("v", v);
            else if (v instanceof Long) item.put("t", "l").put("v", v);
            else if (v instanceof Float) item.put("t", "f").put("v", ((Float) v).doubleValue());
            else if (v instanceof Set) { JSONArray a = new JSONArray(); for (Object o : (Set<?>) v) a.put(String.valueOf(o)); item.put("t", "set").put("v", a); }
            else continue;
            out.put(e.getKey(), item);
        }
        return out;
    }

    private static int load(SharedPreferences.Editor edit, JSONObject values, Set<String> only) {
        int count = 0;
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (only != null && !only.contains(key)) continue;
            JSONObject item = values.optJSONObject(key);
            if (item == null) continue;
            switch (item.optString("t")) {
                case "s" -> edit.putString(key, item.optString("v"));
                case "b" -> edit.putBoolean(key, item.optBoolean("v"));
                case "i" -> edit.putInt(key, item.optInt("v"));
                case "l" -> edit.putLong(key, item.optLong("v"));
                case "f" -> edit.putFloat(key, (float) item.optDouble("v"));
                case "set" -> { Set<String> s = new HashSet<>(); JSONArray a = item.optJSONArray("v"); if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i)); edit.putStringSet(key, s); }
                default -> { continue; }
            }
            count++;
        }
        return count;
    }
}
