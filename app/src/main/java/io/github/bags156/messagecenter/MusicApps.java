package io.github.bags156.messagecenter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The music and podcast apps you listen with (chosen the first time you tap "Open in…" under a
 * song, or in Settings → Messages), and where each shared song, album, artist, or podcast is in
 * them. Each link is looked up once, in the background, when it arrives or is first shown, and
 * the answer is kept: a month for an exact match, three days for a search (a song out today may
 * be listed exactly later), a day when nothing was found. A failed connection isn't kept.
 */
final class MusicApps {
    private MusicApps() {}

    static final String MUSIC = "music_app", PODCASTS = "podcast_app"; // "" asks the first time; "off" leaves links alone

    interface Done { void done(JSONObject result); }

    private static final ExecutorService pool = Executors.newSingleThreadExecutor(); // one at a time: MusicBrainz asks for a request a second
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, JSONObject> memory = new HashMap<>();
    private static final Map<String, List<Done>> waiting = new HashMap<>();
    private static final long DAY = 24L * 60 * 60 * 1000;

    /** A song, album, artist, or podcast link (not a playlist: a person's own playlist has no match elsewhere). */
    static MusicLinks.Link link(String url) {
        MusicLinks.Link l = MusicLinks.parse(url);
        return l == null || l.kind == MusicLinks.Kind.PLAYLIST ? null : l;
    }

    /** Podcasts open in the podcast app. A short link isn't known until it's followed: Pocket Casts' are podcasts, the rest music. */
    static boolean podcast(MusicLinks.Link l) { return l.kind != null ? l.kind.podcast() : l.app == MusicLinks.App.POCKET_CASTS; }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("paired", Context.MODE_PRIVATE); }
    static MusicLinks.App chosen(Context c, boolean podcast) { return MusicLinks.App.named(prefs(c).getString(podcast ? PODCASTS : MUSIC, "")); }
    static boolean off(Context c, boolean podcast) { return "off".equals(prefs(c).getString(podcast ? PODCASTS : MUSIC, "")); }
    /** null: leave these links as they are. */
    static void choose(Context c, boolean podcast, MusicLinks.App app) { prefs(c).edit().putString(podcast ? PODCASTS : MUSIC, app == null ? "off" : app.name()).apply(); }

    static String label(Context c, boolean podcast) {
        if (off(c, podcast)) return "Off";
        MusicLinks.App app = chosen(c, podcast);
        return app == null ? "Ask the first time" : app.label;
    }

    static boolean installed(Context c, MusicLinks.App app) {
        if (app.pkg.isEmpty()) return false;
        try { c.getPackageManager().getPackageInfo(app.pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** The apps to choose from: the ones on this phone first. */
    static List<MusicLinks.App> choices(Context c, boolean podcast) {
        List<MusicLinks.App> here = new ArrayList<>(), others = new ArrayList<>();
        for (MusicLinks.App a : MusicLinks.App.values()) {
            if (podcast ? !a.podcasts : !a.music) continue;
            (installed(c, a) ? here : others).add(a);
        }
        here.addAll(others);
        return here;
    }

    private static String key(String url, MusicLinks.App app) { return app.name() + " " + url; }
    private static File file(Context c, String url, MusicLinks.App app) { return new File(new File(c.getCacheDir(), "music-v1"), MediaCache.hash(key(url, app)) + ".json"); }

    /** What's known already (in memory, or saved from before), or null. Has "url", "alt", "how" (exact, show, search, none) and "kind". */
    static JSONObject peek(Context c, String url, MusicLinks.App app) {
        String k = key(url, app);
        synchronized (memory) { JSONObject hit = memory.get(k); if (hit != null) return hit; }
        File f = file(c, url, app);
        if (!f.isFile()) return null;
        try {
            JSONObject saved = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (System.currentTimeMillis() - saved.optLong("at") > keep(saved.optString("how"))) return null;
            synchronized (memory) { memory.put(k, saved); }
            return saved;
        } catch (Exception e) { return null; }
    }

    private static long keep(String how) {
        switch (how) { case "exact": case "show": return 30 * DAY; case "search": return 3 * DAY; case "none": return DAY; default: return 0; }
    }

    /** Finds it in the background; done (if any) runs on the main thread. */
    static void find(Context context, String url, MusicLinks.App app, Done done) {
        Context c = context.getApplicationContext();
        JSONObject ready = peek(c, url, app);
        if (ready != null) { if (done != null) main.post(() -> done.done(ready)); return; }
        String k = key(url, app);
        synchronized (memory) {
            List<Done> list = waiting.get(k);
            if (list != null) { if (done != null) list.add(done); return; }
            list = new ArrayList<>(); if (done != null) list.add(done); waiting.put(k, list);
        }
        pool.execute(() -> {
            JSONObject result = lookUp(c, url, app);
            boolean failed = "error".equals(result.optString("how"));
            if (!failed) save(c, url, app, result);
            List<Done> list;
            synchronized (memory) {
                if (!failed) memory.put(k, result);
                list = waiting.remove(k);
            }
            if (list != null) for (Done d : list) main.post(() -> d.done(result));
        });
    }

    private static JSONObject lookUp(Context c, String url, MusicLinks.App app) {
        JSONObject out = new JSONObject();
        long start = System.currentTimeMillis();
        try {
            MusicLinks.Net net = new MusicLinks.Http("MessageCenter/" + version(c) + " ( https://github.com/bags156/message-center )");
            out.put("at", start);
            MusicLinks.Link link = MusicLinks.expand(MusicLinks.parse(url), net);
            MusicLinks.Item item = link == null ? null : MusicLinks.identify(link, net);
            if (link != null && link.app == app) { out.put("how", "exact").put("url", url).put("kind", link.kind.name()); return out; }
            MusicLinks.Match m = item == null ? null : MusicLinks.find(item, app, net, java.util.Locale.getDefault().getCountry());
            out.put("kind", item == null ? (link == null || link.kind == null ? "" : link.kind.name()) : item.kind.name());
            if (m == null) out.put("how", "none");
            else out.put("how", m.how).put("url", m.url).put("alt", m.alt);
            // For the problem report: how it went, never what the song was.
            Diagnostics.note("Music link: " + (link == null ? "?" : link.app.name()) + " → " + app.name() + " " + out.optString("how") + " in " + (System.currentTimeMillis() - start) + " ms");
        } catch (Exception e) {
            try { out.put("how", "error"); } catch (Exception ignored) {}
            Diagnostics.note("Music link lookup failed: " + e.getClass().getSimpleName());
        }
        return out;
    }

    private static void save(Context c, String url, MusicLinks.App app, JSONObject result) {
        try { File f = file(c, url, app); File dir = f.getParentFile(); if (dir != null && !dir.isDirectory()) dir.mkdirs(); Files.write(f.toPath(), result.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) {}
    }

    private static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "1"; }
    }

    /** A song link arrived: look it up now, so it's ready by the time the chat is opened. */
    static void arrived(Context c, String text) {
        if (!prefs(c).getBoolean("link_previews", true)) return;
        String url = ChatText.firstUrl(text);
        MusicLinks.Link l = url.isEmpty() ? null : link(url);
        if (l == null) return;
        MusicLinks.App app = chosen(c, podcast(l));
        if (app != null && app != l.app) find(c, url, app, null);
    }

    /** Opens the match in the app: the app itself when it's on this phone, otherwise its web page. */
    static boolean open(Activity a, JSONObject result, MusicLinks.App app) {
        String url = result.optString("url"), alt = result.optString("alt");
        boolean here = installed(a, app);
        if (here) for (String u : new String[]{url, alt}) {
            if (u.isEmpty()) continue;
            Intent pinned = new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.pkg);
            if (pinned.resolveActivity(a.getPackageManager()) == null) continue;
            try { a.startActivity(pinned); return true; } catch (Exception ignored) {}
        }
        for (String u : new String[]{url, alt}) {
            if (u.isEmpty()) continue;
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE)); return true; } catch (Exception ignored) {}
        }
        return false;
    }

    /** "Same song", "Same show", "The show", "Search": how close the match is, for the button. */
    static String how(JSONObject r) {
        String how = r.optString("how");
        if (how.equals("search")) return "Search";
        if (how.equals("show")) return "The show";
        if (!how.equals("exact")) return "";
        switch (r.optString("kind")) {
            case "ALBUM": return "Same album";
            case "ARTIST": return "Same artist";
            case "SHOW": return "Same show";
            case "EPISODE": return "Same episode";
            default: return "Same song";
        }
    }
}
