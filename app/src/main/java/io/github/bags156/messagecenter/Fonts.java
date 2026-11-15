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
    static File file(Context c) { return new File(new File(c.getFilesDir(), "fonts"), "custom-font"); }
    static boolean has(Context c) { return file(c).isFile(); }
    /** Its name ("Inter"), from the file you picked. */
    static String name(Context c) { return prefs(c).getString("font_name", "Your font"); }

    /** Where the font is used. New fonts start everywhere but Settings (so this page always reads plainly unless you choose). */
    static Set<String> areas(Context c) {
        String saved = prefs(c).getString("font_areas", null);
        Set<String> on = new LinkedHashSet<>();
        if (saved == null) { for (String[] a : AREAS) if (!a[0].equals("settings")) on.add(a[0]); return on; }
        for (String a : saved.split(",")) if (!a.isEmpty()) on.add(a);
        return on;
    }
    static void setArea(Context c, String area, boolean on) {
        Set<String> all = areas(c);
        if (on) all.add(area); else all.remove(area);
        prefs(c).edit().putString("font_areas", String.join(",", all)).apply();
        settingsChanged();
    }

    // The loaded font, and its bold/italic styles; reloaded when the file or the areas change.
    // (Only changed through here, so the file is looked at again only after a change: no disk reads per screen update.)
    private static Typeface loaded; private static volatile boolean fresh; private static Set<String> loadedAreas;
    private static final Map<Integer, Typeface> styles = new HashMap<>();
    /** Whether a font was ever put on this run's views (so turning it off can put the phone's back). */
    private static boolean everApplied;

    private static void settingsChanged() { fresh = false; }

    /** The font (with the phone's font behind it for missing letters), or null if there's none or it won't load. */
    static Typeface typeface(Context c) {
        if (fresh) return loaded;
        fresh = true;
        File f = file(c);
        styles.clear(); loadedAreas = areas(c);
        loaded = f.isFile() ? load(f) : null;
        return loaded;
    }

    private static Typeface load(File f) {
        try {
            Font font = new Font.Builder(f).build();
            return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build()).setSystemFallback("sans-serif").build();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Copies the picked file in as the app's font, once Android can read it as one. Returns its
     * name; throws with a message to show when it isn't a font Android can use.
     */
    static String install(Context c, Uri uri, String pickedName) throws IOException {
        File dir = file(c).getParentFile();
        if (dir != null) dir.mkdirs();
        File incoming = new File(dir, "incoming");
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
    static String install(Context c, byte[] data, String name) throws IOException {
        File dir = file(c).getParentFile();
        if (dir != null) dir.mkdirs();
        File incoming = new File(dir, "incoming");
        try (OutputStream out = new FileOutputStream(incoming)) { out.write(data); }
        return adopt(c, incoming, name == null || name.isEmpty() ? "Your font" : name);
    }

    private static String adopt(Context c, File incoming, String name) throws IOException {
        if (!looksLikeFont(incoming) || load(incoming) == null) {
            incoming.delete();
            throw new IOException("That isn't a font Android can use. Pick a .ttf or .otf file (web fonts like .woff won't work).");
        }
        if (!incoming.renameTo(file(c))) { incoming.delete(); throw new IOException("The font couldn't be saved."); }
        prefs(c).edit().putString("font_name", name).apply();
        settingsChanged();
        return name;
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
        if (dot > 0) n = n.substring(0, dot);
        n = n.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
        return n.isEmpty() ? "Your font" : n.length() > 40 ? n.substring(0, 40) : n;
    }

    static void remove(Context c) {
        file(c).delete();
        prefs(c).edit().remove("font_name").apply();
        settingsChanged();
    }

    /** Marks a part of a screen as one of the {@link #AREAS} (everything inside it, unless marked otherwise). */
    static void area(View v, String area) { if (v != null) v.setTag(R.id.font_area, area); }

    /** Keeps the text under root in the right font as views come and go. defaultArea covers anything not marked. */
    static void follow(Context c, View root, String defaultArea) {
        apply(c, root, defaultArea);
        // Listens on the window only while root is in it (a screen replaced by the next one stops listening).
        View.OnAttachStateChangeListener watch = new View.OnAttachStateChangeListener() {
            ViewTreeObserver window;
            final ViewTreeObserver.OnGlobalLayoutListener changed = () -> apply(c, root, defaultArea);
            @Override public void onViewAttachedToWindow(View v) { window = v.getViewTreeObserver(); window.addOnGlobalLayoutListener(changed); apply(c, root, defaultArea); }
            @Override public void onViewDetachedFromWindow(View v) { if (window != null && window.isAlive()) window.removeOnGlobalLayoutListener(changed); window = null; }
        };
        root.addOnAttachStateChangeListener(watch);
        if (root.isAttachedToWindow()) watch.onViewAttachedToWindow(root);
    }

    /** Puts the font on the text under root where it's chosen, and the phone's back where it no longer is. */
    static void apply(Context c, View root, String defaultArea) {
        Typeface font = typeface(c);
        if (font == null && !everApplied) return;
        walk(root, defaultArea, font, loadedAreas);
    }

    private static void walk(View v, String area, Typeface font, Set<String> on) {
        Object marked = v.getTag(R.id.font_area);
        if (marked instanceof String) area = (String) marked;
        if (v instanceof TextView) use((TextView) v, font != null && on != null && on.contains(area) ? font : null);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), area, font, on);
        }
    }

    /** The font for one piece of text, keeping its weight and slant; null puts back what the app chose. */
    private static void use(TextView t, Typeface font) {
        Typeface now = t.getTypeface(), mine = (Typeface) t.getTag(R.id.font_set), theirs = (Typeface) t.getTag(R.id.font_original);
        // If the app changed this text's typeface since (bold for unread, say), that's the one to follow.
        if (mine == null || now != mine) theirs = now == null ? Typeface.DEFAULT : now;
        if (font != null && theirs != Typeface.MONOSPACE) {
            Typeface want = styled(font, theirs);
            t.setTag(R.id.font_original, theirs); t.setTag(R.id.font_set, want);
            if (now != want) t.setTypeface(want);
            everApplied = true;
        } else if (mine != null) {
            if (now == mine) t.setTypeface(theirs);
            t.setTag(R.id.font_set, null); t.setTag(R.id.font_original, null);
        }
    }

    private static Typeface styled(Typeface font, Typeface like) {
        boolean bold = like.getWeight() >= 500 || like.isBold(), italic = like.isItalic();
        int key = (bold ? 1 : 0) | (italic ? 2 : 0);
        Typeface t = styles.get(key);
        if (t == null) { t = key == 0 ? font : Typeface.create(font, bold ? 700 : 400, italic); styles.put(key, t); }
        return t;
    }
}
