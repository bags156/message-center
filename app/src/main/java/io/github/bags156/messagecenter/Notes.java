package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Your private notes about a person or a group: gift ideas, birthdays, things they mentioned to
 * follow up on. Kept on this phone only (and in your backups), never sent. A person's notes are
 * keyed by their number, so they're the same in your chat with them and from any group they're
 * in; a group's notes belong to that group's chat.
 */
final class Notes {
    static final String PREFS = "notes";
    private final SharedPreferences prefs;

    Notes(Context context) { prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** The key for a person's notes: their number's last 10 digits, like nicknames; "" without a number. */
    static String personKey(String number) {
        String d = ChatText.digits(number == null ? "" : number);
        if (d.length() > 10) d = d.substring(d.length() - 10);
        return d.isEmpty() ? "" : "p:" + d;
    }

    static String groupKey(String conversationID) {
        return conversationID == null || conversationID.isEmpty() ? "" : "g:" + conversationID;
    }

    String text(String key) {
        JSONObject o = read(key);
        return o == null ? "" : o.optString("text");
    }

    /** When these notes were last changed (ms), or 0. */
    long updated(String key) {
        JSONObject o = read(key);
        return o == null ? 0 : o.optLong("updated");
    }

    boolean has(String key) { return !text(key).trim().isEmpty(); }

    /** Saves the notes (blank removes them). Unchanged notes keep their old "edited" time. */
    void set(String key, String text, long now) {
        if (key == null || key.isEmpty()) return;
        String t = text == null ? "" : text;
        if (t.trim().isEmpty()) { prefs.edit().remove(key).apply(); return; }
        if (t.equals(text(key))) return;
        try { prefs.edit().putString(key, new JSONObject().put("text", t).put("updated", now).toString()).apply(); }
        catch (org.json.JSONException ignored) {}
    }

    /** Adds a line at the end of the notes. */
    void add(String key, String line, long now) { set(key, appended(text(key), line), now); }

    /** Every saved note by key. */
    Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            JSONObject o = parse(e.getValue());
            if (o != null && !o.optString("text").trim().isEmpty()) out.put(e.getKey(), o.optString("text"));
        }
        return out;
    }

    private JSONObject read(String key) { return key == null || key.isEmpty() ? null : parse(prefs.getString(key, null)); }

    private static JSONObject parse(Object value) {
        if (!(value instanceof String)) return null;
        try { return new JSONObject((String) value); } catch (org.json.JSONException e) { return null; }
    }

    /** The notes with a line added on the end. */
    static String appended(String notes, String line) {
        String n = notes == null ? "" : notes.replaceAll("\\s+$", "");
        String l = line == null ? "" : line.trim();
        if (l.isEmpty()) return n;
        return n.isEmpty() ? l : n + "\n" + l;
    }

    /**
     * A message saved to notes: "• Nov 12: Wants the blue scarf". context says whose it was or
     * where, like "(you)" or "in Family"; "" when it's the person the notes are about.
     */
    static String clip(String date, String context, String text) {
        String words = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        String c = context == null ? "" : context.trim();
        return "• " + date + (c.isEmpty() ? "" : " " + c) + ": " + words;
    }

    /** Whether the notes contain what was searched for, ignoring case and accents. */
    static boolean matches(String notes, String query) {
        String q = ContactSearch.fold(query);
        return !q.isEmpty() && ContactSearch.fold(notes).contains(q);
    }

    /** The line of the notes with what was searched for in it, cut to about 90 characters around it. */
    static String snippet(String notes, String query) {
        String q = ContactSearch.fold(query);
        String[] lines = (notes == null ? "" : notes).split("\n");
        String hit = "";
        for (String line : lines) {
            if (!line.trim().isEmpty() && hit.isEmpty()) hit = line.trim();
            if (!q.isEmpty() && ContactSearch.fold(line).contains(q)) { hit = line.trim(); break; }
        }
        if (hit.length() <= 90) return hit;
        int at = Math.max(0, ContactSearch.fold(hit).indexOf(q));
        int start = Math.max(0, Math.min(at - 30, hit.length() - 90));
        String cut = hit.substring(start, Math.min(hit.length(), start + 90)).trim();
        return (start > 0 ? "…" : "") + cut + (start + 90 < hit.length() ? "…" : "");
    }
}
