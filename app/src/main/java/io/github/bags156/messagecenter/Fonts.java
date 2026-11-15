package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A font of your own (a .ttf or .otf you pick), used in the parts of the app you choose. Kept in
 * the app's private storage. Letters the font doesn't have (emoji, other alphabets) come from the
 * phone's font. Screens and menus mark their parts with {@link #area}; {@link #follow} keeps a
 * screen's text in the right font as it changes.
 */
final class Fonts {
    private Fonts() {}

    /** The parts of the app a font can be used in: key, name, what it covers. */
    static final List<String[]> AREAS = Arrays.asList(
        new String[]{"messages", "Messages", "What's said in chats"},
        new String[]{"composer", "Message box", "What you type, and the replies above it"},
        new String[]{"list", "Chat list", "Names, previews and search on the main screen"},
        new String[]{"titles", "Titles", "Screen titles and the name at the top of a chat"},
        new String[]{"menus", "Menus and pop-ups", "Hold menus, ⋮ menus and sheets"},
        new String[]{"settings", "Settings", "These pages"},
        new String[]{"other", "Everything else", "Details, notes, and the rest of a chat's screen"});

    static final long MAX_BYTES = 25L * 1024 * 1024;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("paired", Context.MODE_PRIVATE); }
    private static File dir(Context c) { return new File(c.getFilesDir(), "fonts"); }

    /**
     * An uploaded font. moved: how many characters it only pretends to have (a demo font's DEMO
     * marks), which use the phone's font instead; examples names a few. -1 until checked.
     */
    static final class Entry {
        final String id, name, sha, examples; final int moved;
        Entry(String id, String name, String sha, int moved, String examples) { this.id = id; this.name = name; this.sha = sha; this.moved = moved; this.examples = examples; }
        JSONObject json() throws JSONException { return new JSONObject().put("id", id).put("name", name).put("sha", sha).put("moved", moved).put("examples", examples); }
    }

    static File fileFor(Context c, String id) { return new File(dir(c), id + ".font"); }

    /** Every font you've uploaded, oldest first. They stay until you delete them. */
    static synchronized List<Entry> library(Context c) {
        migrate(c);
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(prefs(c).getString("font_library", "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                if (fileFor(c, o.optString("id")).isFile())
                    out.add(new Entry(o.optString("id"), o.optString("name", "Your font"), o.optString("sha"), o.optInt("moved", -1), o.optString("examples")));
            }
        } catch (JSONException ignored) {}
        return out;
    }
    private static synchronized void save(Context c, List<Entry> all) {
        JSONArray out = new JSONArray();
        try { for (Entry e : all) out.put(e.json()); } catch (JSONException ignored) {}
        prefs(c).edit().putString("font_library", out.toString()).apply();
    }
    static Entry entry(Context c, String id) { for (Entry e : library(c)) if (e.id.equals(id)) return e; return null; }

    /** Which uploaded font a part of the app uses ("" for the phone's own). */
    static String fontFor(Context c, String area) {
        migrate(c);
        String id = prefs(c).getString("font_for:" + area, "");
        return id.isEmpty() || !fileFor(c, id).isFile() ? "" : id;
    }
    static void setFontFor(Context c, String area, String id) { prefs(c).edit().putString("font_for:" + area, id).apply(); settingsChanged(); }
    /** Uses this font in every part of the app. */
    static void useEverywhere(Context c, String id) { SharedPreferences.Editor e = prefs(c).edit(); for (String[] a : AREAS) e.putString("font_for:" + a[0], id); e.apply(); settingsChanged(); }
    /** Whether any part of the app uses an uploaded font. */
    static boolean has(Context c) { for (String[] a : AREAS) if (!fontFor(c, a[0]).isEmpty()) return true; return false; }
    /** The parts of the app that use this font ("Messages", "Titles"). */
    static List<String> usedFor(Context c, String id) { List<String> out = new ArrayList<>(); for (String[] a : AREAS) if (fontFor(c, a[0]).equals(id)) out.add(a[1]); return out; }
    /** The fonts in use, by name ("Inter + Lora"), for Settings' summary. */
    static String inUse(Context c) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String[] a : AREAS) { Entry e = entry(c, fontFor(c, a[0])); if (e != null) names.add(e.name); }
        return String.join(" + ", names);
    }
    /** An uploaded font's name, or "Phone's font". */
    static String nameOf(Context c, String id) { Entry e = id.isEmpty() ? null : entry(c, id); return e == null ? "Phone's font" : e.name; }
    /** The parts a newly uploaded font starts in, when no part uses one yet: everywhere but Settings. */
    private static final String[] FIRST_AREAS = {"messages", "composer", "list", "titles", "menus", "other"};

    /** Deletes an uploaded font; if it was in use, the phone's font is used. */
    static synchronized void delete(Context c, String id) {
        List<Entry> all = library(c);
        all.removeIf(e -> e.id.equals(id));
        save(c, all);
        fileFor(c, id).delete();
        samples.remove(id);
        SharedPreferences.Editor ed = prefs(c).edit();
        for (String[] a : AREAS) if (prefs(c).getString("font_for:" + a[0], "").equals(id)) ed.putString("font_for:" + a[0], "");
        ed.apply();
        settingsChanged();
    }

    /** 1.5.53 kept one font, as fonts/custom-font: it becomes the first in the library, still in use. */
    private static void migrate(Context c) {
        File old = new File(dir(c), "custom-font");
        if (!old.isFile()) return;
        String id = "f" + old.lastModified();
        if (!old.renameTo(fileFor(c, id))) return;
        List<Entry> all = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(prefs(c).getString("font_library", "[]"));
            for (int i = 0; i < saved.length(); i++) { JSONObject o = saved.getJSONObject(i); all.add(new Entry(o.optString("id"), o.optString("name"), o.optString("sha"), o.optInt("moved", -1), o.optString("examples"))); }
        } catch (JSONException ignored) {}
        all.add(new Entry(id, prefs(c).getString("font_name", "Your font"), "", -1, ""));
        save(c, all);
        String areas = prefs(c).getString("font_areas", null);
        SharedPreferences.Editor ed = prefs(c).edit().remove("font_name").remove("font_areas");
        for (String a : areas == null ? FIRST_AREAS : areas.split(",")) if (!a.isEmpty()) ed.putString("font_for:" + a, id);
        ed.apply();
    }

    // The font each part of the app uses, and their bold/italic styles; looked up again only after a
    // change made here (so no disk reads on each screen update). Samples: each uploaded font, loaded once.
    private static volatile boolean fresh;
    private static final Map<String, Typeface> byArea = new HashMap<>();
    private static final Map<Typeface, Typeface[]> styles = new HashMap<>();
    private static final Map<String, Typeface> samples = new HashMap<>();
    /** Whether a font was ever put on this run's views (so turning it off can put the phone's back). */
    private static boolean everApplied;

    private static void settingsChanged() { fresh = false; }

    private static void refresh(Context c) {
        if (fresh) return;
        fresh = true;
        byArea.clear(); styles.clear();
        for (String[] a : AREAS) { String id = fontFor(c, a[0]); Typeface t = id.isEmpty() ? null : sample(c, id); if (t != null) byArea.put(a[0], t); }
    }

    /** The font a part of the app uses (with the phone's font behind it for missing letters), or null for the phone's own. */
    static Typeface typeface(Context c, String area) { refresh(c); return byArea.get(area); }

    /** An uploaded font by itself (for its row in Settings), or null if it won't load. */
    static Typeface sample(Context c, String id) {
        if (samples.containsKey(id)) return samples.get(id);
        check(c, id);
        Typeface t = load(fileFor(c, id));
        samples.put(id, t);
        return t;
    }

    private static Typeface load(File f) {
        try {
            Font font = new Font.Builder(f).build();
            return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build()).setSystemFallback("sans-serif").build();
        } catch (Exception e) {
            return null;
        }
    }

    /** Fonts from before this check (moved -1) get it once: their stand-in marks are taken out. */
    private static void check(Context c, String id) {
        Entry e = entry(c, id);
        if (e == null || e.moved >= 0) return;
        File f = fileFor(c, id);
        int moved = 0; String examples = "";
        try {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            FontFile.Fixed fixed = FontFile.fix(data);
            if (fixed.data != data) {
                File tmp = new File(dir(c), "fixing");
                java.nio.file.Files.write(tmp.toPath(), fixed.data);
                if (load(tmp) != null && tmp.renameTo(f)) { moved = fixed.moved.size(); examples = FontFile.examples(fixed.moved, 3); }
                else tmp.delete();
            }
        } catch (IOException ignored) {}
        List<Entry> all = library(c);
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(id)) all.set(i, new Entry(id, e.name, e.sha, moved, examples));
        save(c, all);
    }

    /**
     * Copies the picked file in as a new font, once Android can read it as one (the first one goes
     * straight into use). Throws with a message to show when it isn't a font Android can use.
     */
    static Entry install(Context c, Uri uri, String pickedName) throws IOException {
        dir(c).mkdirs();
        File incoming = new File(dir(c), "incoming");
        long total = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(incoming)) {
            if (in == null) throw new IOException("That file couldn't be opened.");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("That file is too big to be a font (over 25 MB).");
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            incoming.delete();
            throw e;
        }
        return adopt(c, incoming, cleanName(pickedName));
    }

    /** The same from a backup: the font's bytes and name. */
    static Entry install(Context c, byte[] data, String name) throws IOException {
        dir(c).mkdirs();
        File incoming = new File(dir(c), "incoming");
        try (OutputStream out = new FileOutputStream(incoming)) { out.write(data); }
        return adopt(c, incoming, name == null || name.isEmpty() ? "Your font" : name);
    }

    /** Checks the file is a font, adds it to the library (or finds it there, if it's one you already uploaded) and uses it. */
    private static synchronized Entry adopt(Context c, File incoming, String name) throws IOException {
        if (!looksLikeFont(incoming) || load(incoming) == null) {
            incoming.delete();
            throw new IOException("That isn't a font Android can use. Pick a .ttf or .otf file (web fonts like .woff won't work).");
        }
        byte[] data = java.nio.file.Files.readAllBytes(incoming.toPath());
        String sha = sha(data);
        for (Entry e : library(c)) if (e.sha.equals(sha)) { incoming.delete(); return e; }
        String id = "f" + System.currentTimeMillis();
        while (fileFor(c, id).exists()) id = id + "x";
        FontFile.Fixed fixed = FontFile.fix(data);
        int moved = 0; String examples = "";
        if (fixed.data != data) {
            File tmp = new File(dir(c), "fixing");
            java.nio.file.Files.write(tmp.toPath(), fixed.data);
            if (load(tmp) != null && tmp.renameTo(incoming)) { moved = fixed.moved.size(); examples = FontFile.examples(fixed.moved, 3); }
            else tmp.delete();
        }
        if (!incoming.renameTo(fileFor(c, id))) { incoming.delete(); throw new IOException("The font couldn't be saved."); }
        Entry e = new Entry(id, name, sha, moved, examples);
        List<Entry> all = library(c);
        all.add(e);
        save(c, all);
        if (!has(c)) { SharedPreferences.Editor ed = prefs(c).edit(); for (String a : FIRST_AREAS) ed.putString("font_for:" + a, id); ed.apply(); }
        settingsChanged();
        return e;
    }

    private static String sha(byte[] data) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { return ""; }
    }

    /** A TrueType/OpenType font or collection starts with one of these. */
    private static boolean looksLikeFont(File f) {
        byte[] head = new byte[4];
        try (InputStream in = new java.io.FileInputStream(f)) { if (in.read(head) != 4) return false; } catch (IOException e) { return false; }
        String tag = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1);
        return (head[0] == 0 && head[1] == 1 && head[2] == 0 && head[3] == 0) || tag.equals("OTTO") || tag.equals("true") || tag.equals("ttcf");
    }

    /** "Inter-Regular.ttf" → "Inter Regular". */
    static String cleanName(String picked) {
        String n = picked == null ? "" : picked.trim();
        int dot = n.lastIndexOf('.');
        if (dot >= 0) n = n.substring(0, dot);
        n = n.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
        return n.isEmpty() ? "Your font" : n.length() > 40 ? n.substring(0, 40) : n;
    }

    /** Marks a part of a screen as one of the {@link #AREAS} (everything inside it, unless marked otherwise). */
    static void area(View v, String area) { if (v != null) v.setTag(R.id.font_area, area); }

    /** Keeps the text under root in the right font as views come and go. defaultArea covers anything not marked. */
    static void follow(Context c, View root, String defaultArea) {
        // Listens on the window only while root is in it (a screen replaced by the next one stops listening).
        View.OnAttachStateChangeListener watch = new View.OnAttachStateChangeListener() {
            ViewTreeObserver window;
            // Whether this screen has text in the font: with no font, only a screen that had it is looked through (once, to undo it).
            boolean had;
            // Fonts are put on new text once it's laid out, which needs laying out again. The frame in between
            // would show the new letters in spaces measured for the old ones (cut-off words, "28" as "2"), so
            // it isn't drawn: the screen keeps its last frame until the next, right one (a few ms later).
            int skip, skippedInARow;
            final ViewTreeObserver.OnGlobalLayoutListener changed = () -> {
                refresh(c);
                if (byArea.isEmpty() && !had) return;
                if (walk(root, defaultArea)) skip++;
                had = !byArea.isEmpty();
            };
            final ViewTreeObserver.OnPreDrawListener hold = () -> {
                if (skip == 0 || skippedInARow >= 3) { skip = 0; skippedInARow = 0; return true; }
                skip = 0; skippedInARow++;
                return false;
            };
            @Override public void onViewAttachedToWindow(View v) {
                window = v.getViewTreeObserver();
                window.addOnGlobalLayoutListener(changed); window.addOnPreDrawListener(hold);
                had = everApplied; changed.onGlobalLayout(); skip = 0;
            }
            @Override public void onViewDetachedFromWindow(View v) {
                if (window != null && window.isAlive()) { window.removeOnGlobalLayoutListener(changed); window.removeOnPreDrawListener(hold); }
                window = null;
            }
        };
        root.addOnAttachStateChangeListener(watch);
        if (root.isAttachedToWindow()) watch.onViewAttachedToWindow(root);
    }

    /** Puts each part's font on the text under root, and the phone's back where there's none now. */
    static void apply(Context c, View root, String defaultArea) {
        refresh(c);
        if (byArea.isEmpty() && !everApplied) return;
        walk(root, defaultArea);
    }

    /** Whether any text changed font (so it needs laying out again). */
    private static boolean walk(View v, String area) {
        Object marked = v.getTag(R.id.font_area);
        if (marked instanceof String) area = (String) marked;
        boolean changed = v instanceof TextView && use((TextView) v, byArea.get(area));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) changed |= walk(g.getChildAt(i), area);
        }
        return changed;
    }

    /** Which uploaded font a piece of text is drawn in now (its plain style), or null for the phone's: for the device checks. */
    static Typeface fontOf(TextView t) {
        Object set = t.getTag(R.id.font_set);
        if (set == null || t.getTypeface() != set) return null;
        for (Map.Entry<Typeface, Typeface[]> e : styles.entrySet()) for (Typeface x : e.getValue()) if (x == set) return e.getKey();
        return null;
    }

    /** The font for one piece of text, keeping its weight and slant; null puts back what the app chose. */
    private static boolean use(TextView t, Typeface font) {
        Typeface now = t.getTypeface(), mine = (Typeface) t.getTag(R.id.font_set), theirs = (Typeface) t.getTag(R.id.font_original);
        // If the app changed this text's typeface since (bold for unread, say), that's the one to follow.
        if (mine == null || now != mine) theirs = now == null ? Typeface.DEFAULT : now;
        if (font != null && theirs != Typeface.MONOSPACE) {
            Typeface want = styled(font, theirs);
            t.setTag(R.id.font_original, theirs); t.setTag(R.id.font_set, want);
            everApplied = true;
            if (now != want) { t.setTypeface(want); return true; }
        } else if (mine != null) {
            t.setTag(R.id.font_set, null); t.setTag(R.id.font_original, null);
            if (now == mine) { t.setTypeface(theirs); return true; }
        }
        return false;
    }

    private static Typeface styled(Typeface font, Typeface like) {
        boolean bold = like.getWeight() >= 500 || like.isBold(), italic = like.isItalic();
        int key = (bold ? 1 : 0) | (italic ? 2 : 0);
        Typeface[] four = styles.computeIfAbsent(font, f -> new Typeface[4]);
        if (four[key] == null) four[key] = key == 0 ? font : Typeface.create(font, bold ? 700 : 400, italic);
        return four[key];
    }
}
