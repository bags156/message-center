package io.github.bags156.messagecenter;

import java.util.Arrays;
import java.util.List;

/**
 * Tries music-link matching against the real services (CI runs it; the results are only printed,
 * never a reason to fail the build, since any of these services can be down for a minute).
 */
public final class MusicProbe {
    public static void main(String[] args) throws Exception {
        MusicLinks.Net net = new MusicLinks.Http("MessageCenter/probe ( https://github.com/bags156/message-center )");
        MusicLinks.log = s -> System.out.println("   . " + s);
        List<MusicLinks.App> music = Arrays.asList(MusicLinks.App.SPOTIFY, MusicLinks.App.APPLE_MUSIC, MusicLinks.App.YOUTUBE_MUSIC, MusicLinks.App.DEEZER,
            MusicLinks.App.TIDAL, MusicLinks.App.AMAZON_MUSIC, MusicLinks.App.SOUNDCLOUD);
        List<MusicLinks.App> podcasts = Arrays.asList(MusicLinks.App.APPLE_PODCASTS, MusicLinks.App.POCKET_CASTS, MusicLinks.App.ANTENNAPOD,
            MusicLinks.App.PODCAST_ADDICT, MusicLinks.App.SPOTIFY, MusicLinks.App.YOUTUBE_MUSIC);
        String[] links = args.length > 0 ? args : new String[]{
            "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=abc",
            "https://open.spotify.com/track/003vvx7Niy0yvhvHt4a68B",
            "https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj",
            "https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ",
            "https://www.deezer.com/us/track/908604612",
            "https://podcasts.apple.com/us/podcast/the-daily/id1200361736",
            "https://open.spotify.com/show/3IM0lmZxpFAY7CwMuv9H4g",
            "https://music.youtube.com/watch?v=J7p4bzqLvCw",
            "https://music.apple.com/us/album/blinding-lights/1499378108?i=1499378607",
            "https://tidal.com/track/155054813",
            "https://music.apple.com/us/album/after-hours/1499378108",
            "episodes",
        };
        java.util.List<String> all = new java.util.ArrayList<>();
        // Raw answers ("raw:URL", "post:URL|JSON") and songs by name ("song:Title|Artist|Album") straight to Spotify.
        boolean namedOnly = true;
        for (String a : links) if (!a.startsWith("raw:") && !a.startsWith("post:") && !a.startsWith("song:") && !a.startsWith("find:") && !a.startsWith("finds:") && !a.startsWith("postfind:")) namedOnly = false;
        int songs = 0, exact = 0;
        for (String a : links) {
            if (a.startsWith("raw:")) { String r = net.get(a.substring(4), false); System.out.println("RAW " + a.substring(4) + "\n    " + (r == null ? "nothing" : r.substring(0, Math.min(1500, r.length())).replace("\n", " "))); }
            else if (a.startsWith("find:")) { String[] p = a.substring(5).split("\\|", 2); String r = net.get(p[0], true); int at = r == null ? -1 : r.indexOf(p[1]);
                System.out.println("FIND " + p[1] + " in " + p[0] + " (" + (r == null ? "nothing" : r.length() + " chars") + ")\n    " + (at < 0 ? "not there" : r.substring(Math.max(0, at - 300), Math.min(r.length(), at + 900)))); }
            else if (a.startsWith("finds:") || a.startsWith("postfind:")) {
                // Every place a word shows up (up to 10), with a little around each: "finds:URL|word", "postfind:URL|JSON|word".
                boolean post = a.startsWith("postfind:"); String[] p = a.substring(post ? 9 : 6).split("\\|", post ? 3 : 2); String needle = p[p.length - 1];
                String r = post ? net.post(p[0], p[1], new java.util.HashMap<>()) : net.get(p[0], true);
                System.out.println("FINDS " + needle + " in " + p[0] + " (" + (r == null ? "nothing" : r.length() + " chars") + ")");
                int at = -1, n = 0; while (r != null && n < 10 && (at = r.indexOf(needle, at + 1)) >= 0) { n++; System.out.println("    " + r.substring(Math.max(0, at - 160), Math.min(r.length(), at + 340)).replace("\n", " ")); }
            }
            else if (a.startsWith("post:")) { String[] p = a.substring(5).split("\\|", 2); String r = net.post(p[0], p[1], new java.util.HashMap<>()); System.out.println("POST " + p[0] + "\n    " + (r == null ? "nothing" : r.substring(0, Math.min(1500, r.length())))); }
            else if (a.startsWith("song:")) {
                String[] p = a.substring(5).split("\\|");
                MusicLinks.Item item = new MusicLinks.Item(MusicLinks.Kind.SONG); item.title = p[0]; item.artist = p.length > 1 ? p[1] : ""; item.album = p.length > 2 ? p[2] : "";
                long t = System.currentTimeMillis(); MusicLinks.Match m = MusicLinks.find(item, MusicLinks.App.SPOTIFY, net, "us"); songs++; if (m != null && m.exact()) exact++;
                System.out.println("SONG " + p[0] + " by " + item.artist + " → Spotify " + m + " (" + (System.currentTimeMillis() - t) + " ms)");
            }
        }
        if (songs > 0) System.out.println("SPOTIFY EXACT " + exact + " of " + songs);
        if (namedOnly) return;
        for (String url : links) {
            if (url.startsWith("raw:") || url.startsWith("post:") || url.startsWith("song:") || url.startsWith("find:") || url.startsWith("finds:") || url.startsWith("postfind:")) continue;
            if (!url.equals("episodes")) { all.add(url); continue; }
            // An episode of each show, found on its page.
            String page = net.get("https://open.spotify.com/show/3IM0lmZxpFAY7CwMuv9H4g", true);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:/episode/|spotify:episode:)([A-Za-z0-9]{22})").matcher(page == null ? "" : page);
            if (m.find()) all.add("https://open.spotify.com/episode/" + m.group(1)); else System.out.println("NOTE no Spotify episode on the show page");
            org.json.JSONObject j = MusicLinks.json(net.get("https://itunes.apple.com/lookup?id=1200361736&entity=podcastEpisode&limit=3", false));
            if (j != null && j.optJSONArray("results") != null && j.getJSONArray("results").length() > 1) all.add(j.getJSONArray("results").getJSONObject(1).optString("trackViewUrl"));
        }
        for (String url : all) {
            long start = System.currentTimeMillis();
            System.out.println("LINK " + url);
            try {
                MusicLinks.Link link = MusicLinks.expand(MusicLinks.parse(url), net);
                if (link == null) { System.out.println("  not a music link"); continue; }
                MusicLinks.Item item = MusicLinks.identify(link, net);
                System.out.println("  is " + item);
                if (item == null) continue;
                for (MusicLinks.App app : item.kind.podcast() ? podcasts : music) {
                    if (app == link.app) continue;
                    long t = System.currentTimeMillis();
                    MusicLinks.Match m = MusicLinks.find(copy(item), app, net, "us");
                    System.out.println("  " + app.label + ": " + m + "  (" + (System.currentTimeMillis() - t) + " ms)");
                    // Round trip: the found link named again by its own service.
                    if (m != null && m.exact() && m.url.startsWith("https://")) {
                        MusicLinks.Link back = MusicLinks.parse(m.url);
                        if (back != null) System.out.println("     reads back as " + MusicLinks.identify(back, net));
                    }
                }
            } catch (Exception e) { System.out.println("  failed: " + e); }
            System.out.println("  took " + (System.currentTimeMillis() - start) + " ms");
        }
    }

    private static MusicLinks.Item copy(MusicLinks.Item i) {
        MusicLinks.Item c = new MusicLinks.Item(i.kind);
        c.title = i.title; c.artist = i.artist; c.album = i.album; c.isrc = i.isrc; c.upc = i.upc; c.show = i.show; c.feed = i.feed;
        c.appleShow = i.appleShow; c.appleShowUrl = i.appleShowUrl; c.ms = i.ms;
        return c;
    }
}
