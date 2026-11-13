package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Your private notes about a person or a group: gift ideas, birthdays, things they mentioned to
 * follow up on. Each is dated, can carry your own tags and attached photos or files, and can be
 * pinned. Kept on this phone only, never sent; backups include the words and tags, not the files. A person's notes are keyed by their number, so they're the
 * same in your chat with them and from any group they're in; a group's belong to that group.
 */
final class Notes {
    static final String PREFS = "notes";
    static final int MAX_TAG = 30;

    /** A photo or file kept with a note, in the note's own folder. */
    static final class Attachment {
        final String file, name, mime;
        Attachment(String file, String name, String mime) { this.file = file; this.name = name; this.mime = mime == null || mime.isEmpty() ? "application/octet-stream" : mime; }
        boolean image() { return mime.startsWith("image/"); }
        boolean video() { return mime.startsWith("video/"); }
        JSONObject json() throws JSONException { return new JSONObject().put("file", file).put("name", name).put("mime", mime); }
        static Attachment from(JSONObject o) { return new Attachment(o.optString("file"), o.optString("name"), o.optString("mime")); }
    }

    /** One note. */
    static final class Note {
        String id = "", text = "", source = "";
        List<String> tags = new ArrayList<>();
        List<Attachment> attachments = new ArrayList<>();
        boolean pinned;
        long created, updated;

        Note() {}

        Note(String id, String text, long created) { this.id = id; this.text = text; this.created = created; this.updated = created; }

        boolean blank() { return text.trim().isEmpty() && tags.isEmpty() && attachments.isEmpty(); }

        JSONObject json() throws JSONException {
            JSONObject o = new JSONObject().put("id", id).put("text", text).put("pinned", pinned).put("created", created).put("updated", updated);
            if (!source.isEmpty()) o.put("source", source);
            JSONArray t = new JSONArray();
            for (String tag : tags) t.put(tag);
            JSONArray a = new JSONArray();
            for (Attachment at : attachments) a.put(at.json());
            return o.put("tags", t).put("attachments", a);
        }

        static Note from(JSONObject o) {
            Note n = new Note();
            n.id = o.optString("id"); n.text = o.optString("text"); n.source = o.optString("source");
            n.pinned = o.optBoolean("pinned"); n.created = o.optLong("created"); n.updated = o.optLong("updated", n.created);
            JSONArray t = o.optJSONArray("tags");
            if (t != null) for (int i = 0; i < t.length(); i++) n.tags = withTag(n.tags, t.optString(i));
            JSONArray a = o.optJSONArray("attachments");
            if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject at = a.optJSONObject(i); if (at != null && !at.optString("file").isEmpty()) n.attachments.add(Attachment.from(at)); }
            return n;
        }

        Note copy() { Note n = from(safeJson(this)); return n; }
    }

    private final SharedPreferences prefs;
    private final java.io.File filesDir;

    Notes(Context context) { prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); filesDir = context.getFilesDir(); }

    /** A note's folder for its attachments; null for an id that isn't a plain note id. */
    static java.io.File dir(java.io.File filesDir, String noteId) {
        if (noteId == null || !noteId.matches("[0-9a-z]{1,40}")) return null;
        return new java.io.File(new java.io.File(filesDir, "notes"), noteId);
    }

    java.io.File file(Note note, Attachment a) { java.io.File d = dir(filesDir, note.id); return d == null ? null : new java.io.File(d, a.file); }

    /** A name for a file kept with a note: plain characters only, numbered so it doesn't replace another. */
    static String fileName(String displayName, int number) {
        String n = (displayName == null ? "" : displayName).replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._]+", "");
        if (n.length() > 60) n = n.substring(n.length() - 60);
        return number + "-" + (n.isEmpty() ? "file" : n);
    }

    /** The key for a person's notes: their number's last 10 digits, like nicknames; "" without a number. */
    static String personKey(String number) {
        String d = ChatText.digits(number == null ? "" : number);
        if (d.length() > 10) d = d.substring(d.length() - 10);
        return d.isEmpty() ? "" : "p:" + d;
    }

    static String groupKey(String conversationID) {
        return conversationID == null || conversationID.isEmpty() ? "" : "g:" + conversationID;
    }

    /** This person's (or group's) notes: pinned first, then newest first. */
    List<Note> list(String key) { return sorted(read(key)); }

    boolean has(String key) { return !read(key).isEmpty(); }

    /** The name the notes were last saved under ("Nora"), for showing them where there's no chat. */
    String name(String key) { JSONObject o = load(key); return o == null ? "" : o.optString("name"); }

    /** Adds or replaces the note (by id); a note with no words and no tags is deleted instead. */
    void save(String key, String name, Note note) {
        if (key == null || key.isEmpty() || note == null || note.id.isEmpty()) return;
        List<Note> notes = read(key);
        notes.removeIf(n -> n.id.equals(note.id));
        if (!note.blank()) notes.add(note); else deleteFiles(note.id);
        write(key, name, notes);
    }

    /** Deletes the note and the files kept with it. */
    void delete(String key, String id) {
        List<Note> notes = read(key);
        if (notes.removeIf(n -> n.id.equals(id))) write(key, name(key), notes);
        deleteFiles(id);
    }

    private void deleteFiles(String id) {
        java.io.File d = dir(filesDir, id);
        java.io.File[] files = d == null ? null : d.listFiles();
        if (files != null) for (java.io.File f : files) f.delete();
        if (d != null) d.delete();
    }

    /** Every key with notes, and its notes (sorted). */
    Map<String, List<Note>> all() {
        Map<String, List<Note>> out = new LinkedHashMap<>();
        for (String key : prefs.getAll().keySet()) { List<Note> notes = list(key); if (!notes.isEmpty()) out.put(key, notes); }
        return out;
    }

    /** Every tag you've used, most used first: suggestions when tagging a note. */
    List<String> allTags() {
        List<Note> everything = new ArrayList<>();
        for (List<Note> notes : all().values()) everything.addAll(notes);
        return tagsOf(everything);
    }

    private List<Note> read(String key) {
        List<Note> out = new ArrayList<>();
        JSONObject o = load(key);
        JSONArray a = o == null ? null : o.optJSONArray("notes");
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject n = a.optJSONObject(i); if (n != null) out.add(Note.from(n)); }
        return out;
    }

    private JSONObject load(String key) {
        if (key == null || key.isEmpty()) return null;
        String s = prefs.getString(key, null);
        if (s == null) return null;
        try { return new JSONObject(s); } catch (JSONException e) { return null; }
    }

    private void write(String key, String name, List<Note> notes) {
        if (notes.isEmpty()) { prefs.edit().remove(key).apply(); return; }
        try {
            JSONArray a = new JSONArray();
            for (Note n : sorted(notes)) a.put(n.json());
            prefs.edit().putString(key, new JSONObject().put("name", name == null ? "" : name).put("notes", a).toString()).apply();
        } catch (JSONException ignored) {}
    }

    private static JSONObject safeJson(Note n) { try { return n.json(); } catch (JSONException e) { return new JSONObject(); } }

    /** Pinned notes first, then newest first (by when each was written). */
    static List<Note> sorted(Collection<Note> notes) {
        List<Note> out = new ArrayList<>(notes);
        out.sort((a, b) -> a.pinned != b.pinned ? (a.pinned ? -1 : 1) : Long.compare(b.created, a.created));
        return out;
    }

    /** A tag cleaned up: no "#", single spaces, at most 30 characters; "" if nothing's left. */
    static String tag(String raw) {
        String t = raw == null ? "" : raw.trim();
        while (t.startsWith("#")) t = t.substring(1).trim();
        t = t.replaceAll("\\s+", " ");
        return t.length() > MAX_TAG ? t.substring(0, MAX_TAG).trim() : t;
    }

    /** The tags with this one added, unless it's already there (in any capitals). */
    static List<String> withTag(List<String> tags, String raw) {
        List<String> out = new ArrayList<>(tags);
        String t = tag(raw);
        if (t.isEmpty()) return out;
        for (String have : out) if (have.equalsIgnoreCase(t)) return out;
        out.add(t);
        return out;
    }

    /** Typed tags, split on commas: "gift, Birthday" gives gift and Birthday. */
    static List<String> splitTags(String typed) {
        List<String> out = new ArrayList<>();
        for (String part : (typed == null ? "" : typed).split(",")) out = withTag(out, part);
        return out;
    }

    /** The tags used on these notes, most used first, then alphabetically (one spelling each). */
    static List<String> tagsOf(Collection<Note> notes) {
        Map<String, Integer> count = new HashMap<>();
        Map<String, String> spelling = new LinkedHashMap<>();
        for (Note n : notes) for (String t : n.tags) {
            String k = t.toLowerCase(Locale.ROOT);
            count.merge(k, 1, Integer::sum);
            spelling.putIfAbsent(k, t);
        }
        List<String> keys = new ArrayList<>(spelling.keySet());
        keys.sort((a, b) -> !count.get(a).equals(count.get(b)) ? Integer.compare(count.get(b), count.get(a)) : a.compareTo(b));
        List<String> out = new ArrayList<>();
        for (String k : keys) out.add(spelling.get(k));
        return out;
    }

    static boolean hasTag(Note n, String tag) {
        for (String t : n.tags) if (t.equalsIgnoreCase(tag)) return true;
        return false;
    }

    /**
     * Whether a note matches a search, ignoring case and accents: every word must be in its text,
     * a tag, or where it came from. A word starting with # only matches tags ("#gift" finds notes
     * tagged gift or gift ideas).
     */
    static boolean matches(Note n, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return true;
        String text = ContactSearch.fold(n.text + " \n " + n.source);
        StringBuilder tagText = new StringBuilder();
        for (String t : n.tags) tagText.append(ContactSearch.fold(t)).append('\n');
        for (String word : q.split("\\s+")) {
            boolean tagOnly = word.startsWith("#");
            String w = ContactSearch.fold(tagOnly ? tag(word) : word);
            if (w.isEmpty()) continue;
            boolean inTags = tagText.toString().contains(w);
            if (tagOnly ? !inTags : !(inTags || text.contains(w))) return false;
        }
        return true;
    }

    /** The note's words with more added on a new line (a message added to an existing note). */
    static String appended(String notes, String line) {
        String n = notes == null ? "" : notes.replaceAll("\\s+$", "");
        String l = line == null ? "" : line.trim();
        if (l.isEmpty()) return n;
        return n.isEmpty() ? l : n + "\n" + l;
    }

    /** A new note's id: when it was made, plus a little randomness so two in the same moment differ. */
    static String newId(long now) { return Long.toString(now, 36) + Integer.toString((int) (Math.random() * 1296), 36); }

    /** A message saved as a note says where it came from: "Nora's message, Nov 12" or "Your message in Family, Nov 12". */
    static String source(String whose, String date) { return whose + ", " + date; }

    /** The line of the note with what was searched for in it, cut to about 90 characters around it. */
    static String snippet(String notes, String query) {
        String q = "";
        for (String word : (query == null ? "" : query.trim()).split("\\s+")) if (!word.startsWith("#") && !word.isEmpty()) { q = ContactSearch.fold(word); break; }
        String[] lines = (notes == null ? "" : notes).split("\n");
        String hit = "";
        for (String line : lines) {
            if (!line.trim().isEmpty() && hit.isEmpty()) hit = line.trim();
            if (!q.isEmpty() && ContactSearch.fold(line).contains(q)) { hit = line.trim(); break; }
        }
        if (hit.length() <= 90) return hit;
        int at = q.isEmpty() ? 0 : Math.max(0, ContactSearch.fold(hit).indexOf(q));
        int start = Math.max(0, Math.min(at - 30, hit.length() - 90));
        String cut = hit.substring(start, Math.min(hit.length(), start + 90)).trim();
        return (start > 0 ? "…" : "") + cut + (start + 90 < hit.length() ? "…" : "");
    }
}
