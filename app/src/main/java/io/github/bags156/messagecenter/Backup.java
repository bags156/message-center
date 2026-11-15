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
    private static final String[] STORES = {"starred", "nicknames", "reminders", "scheduled", "chat-settings", "pins", "emoji", "notes"};
    /** App settings worth carrying over (not connection state or one-time prompts). */
    private static final Set<String> SETTINGS = new HashSet<>(java.util.Arrays.asList(
        "mention_name", "send_typing", "voice_speed", "undo_send", "undo_send_seconds", "link_previews", "full_photos", "auto_copy_codes", "theme", "text_scale", "nudge_minutes", "maps_app", "calendar_app", "music_app", "podcast_app", "music_songlink", "smart_replies", "auto_delete_codes", "promotions_separate", "inbox_unread_count", "show_calls", "quick_replies", "quick_replies_on", "alert_keywords", "autoreply_text", "notification_content","photo_quality","photo_text", "font_areas"));

    static String export(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "Message Center").put("backupVersion", VERSION).put("appVersion", BuildConfig.VERSION_NAME).put("created", System.currentTimeMillis());
        JSONObject stores = new JSONObject();
        for (String name : STORES) stores.put(name, dump(context.getSharedPreferences(name, Context.MODE_PRIVATE), null));
        stores.put("paired", dump(context.getSharedPreferences("paired", Context.MODE_PRIVATE), SETTINGS));
        root.put("stores", stores);
        // Your uploaded fonts come along too (up to 40 MB of them), with the parts of the app each is used for.
        JSONArray fonts = new JSONArray(); long size = 0;
        for (Fonts.Entry e : Fonts.library(context)) {
            java.io.File f = Fonts.fileFor(context, e.id);
            if (size + f.length() > 40L * 1024 * 1024) continue;
            size += f.length();
            JSONArray areas = new JSONArray();
            for (String[] a : Fonts.AREAS) if (Fonts.fontFor(context, a[0]).equals(e.id)) areas.put(a[0]);
            fonts.put(new JSONObject().put("name", e.name).put("areas", areas).put("data", android.util.Base64.encodeToString(java.nio.file.Files.readAllBytes(f.toPath()), android.util.Base64.NO_WRAP)));
        }
        if (fonts.length() > 0) root.put("fonts", fonts);
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
        // Fonts: added to the library, each used where it was. (1.5.53 backups have one, as "font", used where its switches said.)
        JSONArray fonts = root.optJSONArray("fonts");
        if (fonts == null && root.optJSONObject("font") != null) {
            JSONObject only = root.optJSONObject("font");
            String areas = settings == null ? "" : settings.optString("font_areas", "messages,composer,list,titles,menus,other");
            JSONArray list = new JSONArray(); for (String a : areas.split(",")) if (!a.isEmpty()) list.put(a);
            fonts = new JSONArray().put(only.put("areas", list));
        }
        if (fonts != null) {
            for (String[] a : Fonts.AREAS) Fonts.setFontFor(context, a[0], "");
            for (int i = 0; i < fonts.length(); i++) {
                JSONObject font = fonts.optJSONObject(i);
                if (font == null || font.optString("data").isEmpty()) continue;
                try {
                    Fonts.Entry e = Fonts.install(context, android.util.Base64.decode(font.optString("data"), android.util.Base64.DEFAULT), font.optString("name")); count++;
                    for (String[] a : Fonts.AREAS) Fonts.setFontFor(context, a[0], Fonts.fontFor(context, a[0]).equals(e.id) ? "" : Fonts.fontFor(context, a[0]));
                    JSONArray areas = font.optJSONArray("areas");
                    for (int k = 0; areas != null && k < areas.length(); k++) Fonts.setFontFor(context, areas.optString(k), e.id);
                } catch (Exception ignored) { /* A font that won't load is left out; everything else is restored. */ }
            }
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
