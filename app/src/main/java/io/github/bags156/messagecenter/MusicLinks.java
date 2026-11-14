package io.github.bags156.messagecenter;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a shared song, album, artist, or podcast in the app you listen with.
 *
 * Nothing here needs an account or a key. Apple's iTunes Search API names Apple links and finds
 * things on Apple Music and Apple Podcasts; Deezer's API names Deezer links, finds songs on Deezer,
 * and gives each song its ISRC (the code every store files a recording under); MusicBrainz, the
 * open music encyclopedia, links that recording to its pages on Spotify, Tidal, Amazon Music, and
 * SoundCloud; YouTube Music's own search finds it there. When none of them finds the exact song,
 * the app's own search opens with its name instead. Only names, IDs and codes are sent, never a
 * message. Plain Java (no Android), so the same code can be tried against the real services in CI.
 */
final class MusicLinks {
    private MusicLinks() {}

    enum Kind {
        SONG("song"), ALBUM("album"), ARTIST("artist"), PLAYLIST("playlist"), SHOW("show"), EPISODE("episode");
        final String word;
        Kind(String word) { this.word = word; }
        boolean podcast() { return this == SHOW || this == EPISODE; }
    }

    enum App {
        SPOTIFY("Spotify", "com.spotify.music", true, true),
        APPLE_MUSIC("Apple Music", "com.apple.android.music", true, false),
        YOUTUBE_MUSIC("YouTube Music", "com.google.android.apps.youtube.music", true, true),
        AMAZON_MUSIC("Amazon Music", "com.amazon.mp3", true, true),
        TIDAL("Tidal", "com.aspiro.tidal", true, false),
        DEEZER("Deezer", "deezer.android.app", true, false),
        SOUNDCLOUD("SoundCloud", "com.soundcloud.android", true, false),
        APPLE_PODCASTS("Apple Podcasts", "", false, true),
        POCKET_CASTS("Pocket Casts", "au.com.shiftyjelly.pocketcasts", false, true),
        PODCAST_ADDICT("Podcast Addict", "com.bambuna.podcastaddict", false, true),
        ANTENNAPOD("AntennaPod", "de.danoeh.antennapod", false, true);

        final String label, pkg; final boolean music, podcasts;
        App(String label, String pkg, boolean music, boolean podcasts) { this.label = label; this.pkg = pkg; this.music = music; this.podcasts = podcasts; }
        static App named(String name) { try { return name == null || name.isEmpty() ? null : valueOf(name); } catch (Exception e) { return null; } }
    }

    /** What a link points at. A short link (spotify.link, on.soundcloud.com) has no kind until it's followed. */
    static final class Link {
        final App app; final Kind kind; final String id, country, parent, url;
        Link(App app, Kind kind, String id, String country, String parent, String url) {
            this.app = app; this.kind = kind; this.id = id == null ? "" : id; this.country = country == null ? "" : country; this.parent = parent == null ? "" : parent; this.url = url;
        }
        boolean isShort() { return kind == null; }
    }

    /** The song (album, artist, show, episode) a link is for, as its service names it. */
    static final class Item {
        Kind kind; String title = "", artist = "", album = "", isrc = "", upc = "", show = "", feed = "", appleShow = "", appleShowUrl = "";
        long ms;
        Item(Kind kind) { this.kind = kind; }
        @Override public String toString() {
            return kind + " \"" + title + "\"" + (artist.isEmpty() ? "" : " by " + artist) + (album.isEmpty() ? "" : " on " + album)
                + (show.isEmpty() ? "" : " from " + show) + (isrc.isEmpty() ? "" : " isrc " + isrc) + (upc.isEmpty() ? "" : " upc " + upc) + (ms > 0 ? " " + ms / 1000 + "s" : "");
        }
    }

    /** Where to open it. how: "exact" (the same song), "show" (the podcast, not its episode), "search" (the app's search). */
    static final class Match {
        final String url, alt, how;
        Match(String url, String alt, String how) { this.url = url; this.alt = alt == null ? "" : alt; this.how = how; }
        boolean exact() { return "exact".equals(how); }
        @Override public String toString() { return how + " " + url + (alt.isEmpty() ? "" : " (or " + alt + ")"); }
    }

    /** The connection to the web; tests answer from saved responses instead. */
    interface Net {
        /** The response, or null when there's none (404, nothing found). page: a web page read like a link preview, not an API call. */
        String get(String url, boolean page) throws Exception;
        String post(String url, String json, Map<String, String> headers) throws Exception;
        /** Where a short link leads, after its redirects. */
        String follow(String url) throws Exception;
    }

    /** Notes for the CI probe (null in the app). */
    static volatile java.util.function.Consumer<String> log;
    private static void note(String s) { java.util.function.Consumer<String> l = log; if (l != null) l.accept(s); }

    // ---------------------------------------------------------------- reading links

    static Link parse(String raw) {
        if (raw == null) return null;
        URI u;
        try { u = new URI(raw.trim()); } catch (Exception e) { return null; }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) return null;
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        List<String> p = segments(u.getRawPath());
        Map<String, String> q = query(u.getRawQuery());
        String url = raw.trim();
        switch (host) {
            case "open.spotify.com": case "play.spotify.com": {
                if (!p.isEmpty() && p.get(0).startsWith("intl-")) p.remove(0);
                if (p.size() >= 2) {
                    Kind k = kindWord(p.get(0), "track", "album", "artist", "playlist", "show", "episode");
                    if (k != null) return new Link(App.SPOTIFY, k, p.get(1), "", "", url);
                }
                return null;
            }
            case "spotify.link": case "spoti.fi":
                return p.isEmpty() ? null : new Link(App.SPOTIFY, null, "", "", "", url);
            case "music.apple.com": case "itunes.apple.com": case "geo.music.apple.com": {
                String cc = "";
                if (!p.isEmpty() && p.get(0).length() == 2) cc = p.remove(0);
                if (p.isEmpty()) return null;
                String type = p.get(0), id = appleId(p);
                switch (type) {
                    case "album":
                        if (q.containsKey("i")) return new Link(App.APPLE_MUSIC, Kind.SONG, q.get("i"), cc, id, url);
                        return id.isEmpty() ? null : new Link(App.APPLE_MUSIC, Kind.ALBUM, id, cc, "", url);
                    case "song": return id.isEmpty() ? null : new Link(App.APPLE_MUSIC, Kind.SONG, id, cc, "", url);
                    case "artist": return id.isEmpty() ? null : new Link(App.APPLE_MUSIC, Kind.ARTIST, id, cc, "", url);
                    case "playlist": return new Link(App.APPLE_MUSIC, Kind.PLAYLIST, p.get(p.size() - 1), cc, "", url);
                    case "podcast": return applePodcast(p, q, cc, url);
                    default: return null;
                }
            }
            case "podcasts.apple.com": {
                String cc = "";
                if (!p.isEmpty() && p.get(0).length() == 2) cc = p.remove(0);
                return !p.isEmpty() && p.get(0).equals("podcast") ? applePodcast(p, q, cc, url) : null;
            }
            case "music.youtube.com": {
                if (p.isEmpty()) return null;
                switch (p.get(0)) {
                    case "watch": return q.containsKey("v") ? new Link(App.YOUTUBE_MUSIC, Kind.SONG, q.get("v"), "", "", url) : null;
                    case "browse":
                        if (p.size() >= 2 && p.get(1).startsWith("MPRE")) return new Link(App.YOUTUBE_MUSIC, Kind.ALBUM, p.get(1), "", "", url);
                        if (p.size() >= 2 && p.get(1).startsWith("UC")) return new Link(App.YOUTUBE_MUSIC, Kind.ARTIST, p.get(1), "", "", url);
                        return null;
                    case "channel": return p.size() >= 2 ? new Link(App.YOUTUBE_MUSIC, Kind.ARTIST, p.get(1), "", "", url) : null;
                    case "playlist": {
                        String list = q.get("list");
                        if (list == null || list.isEmpty()) return null;
                        return new Link(App.YOUTUBE_MUSIC, list.startsWith("OLAK5uy_") ? Kind.ALBUM : Kind.PLAYLIST, list, "", "", url);
                    }
                    default: return null;
                }
            }
            case "tidal.com": case "listen.tidal.com": {
                if (!p.isEmpty() && p.get(0).equals("browse")) p.remove(0);
                if (p.size() >= 2) {
                    Kind k = kindWord(p.get(0), "track", "album", "artist", "playlist", null, null);
                    if (k != null) return new Link(App.TIDAL, k, p.get(1), "", "", url);
                }
                return null;
            }
            case "deezer.com": {
                if (!p.isEmpty() && p.get(0).length() == 2) p.remove(0);
                if (p.size() >= 2) {
                    Kind k = kindWord(p.get(0), "track", "album", "artist", "playlist", "show", "episode");
                    if (k != null && p.get(1).matches("\\d+")) return new Link(App.DEEZER, k, p.get(1), "", "", url);
                }
                return null;
            }
            case "deezer.page.link": case "link.deezer.com": case "dzr.page.link":
                return p.isEmpty() ? null : new Link(App.DEEZER, null, "", "", "", url);
            case "soundcloud.com": case "m.soundcloud.com": {
                if (p.isEmpty() || SOUNDCLOUD_PAGES.contains(p.get(0))) return null;
                if (p.size() == 1) return new Link(App.SOUNDCLOUD, Kind.ARTIST, p.get(0), "", "", url);
                if (p.get(1).equals("sets")) return p.size() >= 3 ? new Link(App.SOUNDCLOUD, Kind.ALBUM, p.get(0) + "/sets/" + p.get(2), "", "", url) : null;
                if (SOUNDCLOUD_TABS.contains(p.get(1))) return new Link(App.SOUNDCLOUD, Kind.ARTIST, p.get(0), "", "", url);
                return new Link(App.SOUNDCLOUD, Kind.SONG, p.get(0) + "/" + p.get(1), "", "", url);
            }
            case "on.soundcloud.com":
                return p.isEmpty() ? null : new Link(App.SOUNDCLOUD, null, "", "", "", url);
            case "pca.st": case "pocketcasts.com": case "play.pocketcasts.com": {
                if (p.isEmpty()) return null;
                if (p.get(0).equals("podcast") && p.size() >= 2) return new Link(App.POCKET_CASTS, Kind.SHOW, p.get(p.size() - 1), "", "", url);
                if (p.get(0).equals("episode") && p.size() >= 2) return new Link(App.POCKET_CASTS, Kind.EPISODE, p.get(p.size() - 1), "", "", url);
                return host.equals("pca.st") && p.size() == 1 ? new Link(App.POCKET_CASTS, null, "", "", "", url) : null;
            }
            default:
                if (host.startsWith("music.amazon.") || (host.startsWith("amazon.") && p.size() >= 2 && p.get(0).equals("music") && p.get(1).equals("player"))) return amazon(p, q, url);
                return null;
        }
    }

    private static final java.util.Set<String> SOUNDCLOUD_PAGES = new java.util.HashSet<>(java.util.Arrays.asList(
        "discover", "search", "you", "stream", "upload", "charts", "pages", "terms-of-use", "settings", "messages", "notifications", "people", "jobs", "mobile", "pro", "imprint", "popular", "tags", "signin", "logout"));
    private static final java.util.Set<String> SOUNDCLOUD_TABS = new java.util.HashSet<>(java.util.Arrays.asList(
        "tracks", "albums", "likes", "reposts", "followers", "following", "popular-tracks", "comments", "spotlight"));

    private static Link applePodcast(List<String> p, Map<String, String> q, String cc, String url) {
        String show = appleId(p);
        if (show.isEmpty()) return null;
        String episode = q.get("i");
        return episode != null && !episode.isEmpty() ? new Link(App.APPLE_PODCASTS, Kind.EPISODE, episode, cc, show, url) : new Link(App.APPLE_PODCASTS, Kind.SHOW, show, cc, "", url);
    }

    /** The number at the end of an Apple path: ".../after-hours/1499378108" or the older ".../id1499378108". */
    private static String appleId(List<String> p) {
        for (int i = p.size() - 1; i >= 1; i--) {
            String s = p.get(i);
            if (s.matches("id\\d+")) return s.substring(2);
            if (s.matches("\\d+")) return s;
        }
        return "";
    }

    private static Link amazon(List<String> p, Map<String, String> q, String url) {
        for (int i = 0; i + 1 < p.size(); i++) {
            String type = p.get(i), id = p.get(i + 1);
            switch (type) {
                case "albums":
                    if (q.containsKey("trackAsin")) return new Link(App.AMAZON_MUSIC, Kind.SONG, q.get("trackAsin"), "", id, url);
                    return new Link(App.AMAZON_MUSIC, Kind.ALBUM, id, "", "", url);
                case "tracks": return new Link(App.AMAZON_MUSIC, Kind.SONG, id, "", "", url);
                case "artists": return new Link(App.AMAZON_MUSIC, Kind.ARTIST, id, "", "", url);
                case "playlists": case "user-playlists": return new Link(App.AMAZON_MUSIC, Kind.PLAYLIST, id, "", "", url);
                case "podcasts":
                    if (i + 3 < p.size() && p.get(i + 2).equals("episodes")) return new Link(App.AMAZON_MUSIC, Kind.EPISODE, p.get(i + 3), "", id, url);
                    return new Link(App.AMAZON_MUSIC, Kind.SHOW, id, "", "", url);
                default: break;
            }
        }
        return null;
    }

    private static Kind kindWord(String w, String track, String album, String artist, String playlist, String show, String episode) {
        if (w.equals(track)) return Kind.SONG;
        if (w.equals(album)) return Kind.ALBUM;
        if (w.equals(artist)) return Kind.ARTIST;
        if (w.equals(playlist)) return Kind.PLAYLIST;
        if (w.equals(show)) return Kind.SHOW;
        if (w.equals(episode)) return Kind.EPISODE;
        return null;
    }

    private static List<String> segments(String rawPath) {
        List<String> out = new ArrayList<>();
        if (rawPath == null) return out;
        for (String s : rawPath.split("/")) if (!s.isEmpty()) out.add(decode(s));
        return out;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String decode(String s) { try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; } }
    static String enc(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return ""; } }

    /** A short link, followed to the full link it stands for (or null). */
    static Link expand(Link link, Net net) throws Exception {
        if (!link.isShort()) return link;
        String to = net.follow(link.url);
        Link full = to == null ? null : parse(to);
        if (full != null && !full.isShort()) return full;
        // Some short links (Spotify's) land on a page that sends the browser on with a script: the full link is in it.
        String page = net.get(link.url, true);
        if (page == null) return null;
        Matcher m = Pattern.compile("https://(?:open\\.spotify\\.com|www\\.deezer\\.com|soundcloud\\.com|pocketcasts\\.com)/[^\"'<>\\s\\\\]+").matcher(page);
        while (m.find()) { Link l = parse(m.group()); if (l != null && !l.isShort()) return l; }
        return null;
    }

    // ---------------------------------------------------------------- naming what a link is for

    static final String ITUNES = "https://itunes.apple.com/";
    static final String DEEZER = "https://api.deezer.com/";
    static final String MB = "https://musicbrainz.org/ws/2/";

    /** Asks the link's own service what it's for. Null when that can't be found out. */
    static Item identify(Link link, Net net) throws Exception {
        if (link == null || link.isShort() || link.kind == Kind.PLAYLIST) return null;
        switch (link.app) {
            case APPLE_MUSIC: case APPLE_PODCASTS: return apple(link, net);
            case DEEZER: return deezer(link, net);
            case SPOTIFY: return spotify(link, net);
            case YOUTUBE_MUSIC: return youtube(link, net);
            case SOUNDCLOUD: {
                JSONObject j = json(net.get("https://soundcloud.com/oembed?format=json&url=" + enc("https://soundcloud.com/" + link.id), false));
                if (j == null) return page(link, net);
                return fromTitle(link.kind, j.optString("title"), "", j.optString("author_name"));
            }
            default: return page(link, net);
        }
    }

    private static Item apple(Link l, Net net) throws Exception {
        String cc = l.country.isEmpty() ? "us" : l.country;
        if (l.kind == Kind.EPISODE || l.kind == Kind.SHOW) {
            String show = l.kind == Kind.SHOW ? l.id : l.parent;
            JSONObject j = json(net.get(ITUNES + "lookup?id=" + show + "&entity=podcastEpisode&limit=200&country=" + cc, false));
            JSONArray r = j == null ? null : j.optJSONArray("results");
            if (r == null || r.length() == 0) return null;
            Item showItem = fromItunes(r.optJSONObject(0));
            if (showItem == null) return null;
            if (l.kind == Kind.SHOW) return showItem;
            for (int i = 1; i < r.length(); i++) {
                JSONObject e = r.optJSONObject(i);
                if (e != null && l.id.equals(String.valueOf(e.optLong("trackId")))) {
                    Item ep = fromItunes(e);
                    if (ep != null) { ep.feed = showItem.feed; ep.appleShow = showItem.appleShow; ep.appleShowUrl = showItem.appleShowUrl; return ep; }
                }
            }
            // An older episode than the last 200: the show is still known.
            Item ep = new Item(Kind.EPISODE); ep.show = showItem.title; ep.feed = showItem.feed; ep.appleShow = showItem.appleShow; ep.appleShowUrl = showItem.appleShowUrl;
            return ep;
        }
        JSONObject j = json(net.get(ITUNES + "lookup?id=" + l.id + "&country=" + cc, false));
        JSONArray r = j == null ? null : j.optJSONArray("results");
        return r == null || r.length() == 0 ? null : fromItunes(r.optJSONObject(0));
    }

    static Item fromItunes(JSONObject r) {
        if (r == null) return null;
        String w = r.optString("wrapperType"), k = r.optString("kind");
        Item i;
        if (w.equals("artist")) { i = new Item(Kind.ARTIST); i.title = r.optString("artistName"); }
        else if (w.equals("collection")) { i = new Item(Kind.ALBUM); i.title = albumName(r.optString("collectionName")); i.artist = r.optString("artistName"); }
        else if (k.equals("podcast")) {
            i = new Item(Kind.SHOW); i.title = r.optString("collectionName", r.optString("trackName")); i.artist = r.optString("artistName");
            i.feed = r.optString("feedUrl"); i.appleShow = id(r, "collectionId"); i.appleShowUrl = clean(r.optString("collectionViewUrl"));
        } else if (w.equals("podcastEpisode") || k.equals("podcast-episode")) {
            i = new Item(Kind.EPISODE); i.title = r.optString("trackName"); i.show = r.optString("collectionName");
            i.appleShow = id(r, "collectionId"); i.feed = r.optString("feedUrl"); i.ms = r.optLong("trackTimeMillis");
        } else if (w.equals("track")) {
            i = new Item(Kind.SONG); i.title = r.optString("trackName"); i.artist = r.optString("artistName");
            i.album = albumName(r.optString("collectionName")); i.ms = r.optLong("trackTimeMillis");
        } else return null;
        return i.title.isEmpty() && i.show.isEmpty() ? null : i;
    }

    private static String id(JSONObject r, String key) { long v = r.optLong(key); return v > 0 ? String.valueOf(v) : ""; }

    private static Item deezer(Link l, Net net) throws Exception {
        String type = l.kind == Kind.SONG ? "track" : l.kind == Kind.SHOW ? "podcast" : l.kind.word;
        JSONObject j = json(net.get(DEEZER + type + "/" + l.id, false));
        if (j == null || j.has("error")) return null;
        Item i = new Item(l.kind);
        switch (l.kind) {
            case SONG:
                i.title = j.optString("title"); i.artist = name(j.optJSONObject("artist")); i.isrc = j.optString("isrc");
                JSONObject album = j.optJSONObject("album"); if (album != null) i.album = album.optString("title");
                i.ms = j.optLong("duration") * 1000; break;
            case ALBUM: i.title = j.optString("title"); i.artist = name(j.optJSONObject("artist")); i.upc = j.optString("upc"); break;
            case ARTIST: i.title = j.optString("name"); break;
            case SHOW: i.title = j.optString("title"); break;
            case EPISODE: i.title = j.optString("title"); JSONObject show = j.optJSONObject("podcast"); if (show != null) i.show = show.optString("title"); break;
            default: return null;
        }
        return i.title.isEmpty() ? null : i;
    }

    private static String name(JSONObject o) { return o == null ? "" : o.optString("name"); }

    private static Item spotify(Link l, Net net) throws Exception {
        String type = l.kind == Kind.SONG ? "track" : l.kind.word;
        String html = net.get("https://open.spotify.com/" + type + "/" + l.id, true);
        if (html != null && log != null) { Map<String, String> meta = metaTags(html); note("spotify title=" + pageTitle(html) + " | og:title=" + meta.get("og:title") + " | og:description=" + meta.get("og:description") + " | musician=" + meta.get("music:musician_description")); }
        Item i = html == null ? null : spotifyPage(l.kind, html);
        if (i != null) return i;
        JSONObject j = json(net.get("https://open.spotify.com/oembed?url=" + enc("https://open.spotify.com/" + type + "/" + l.id), false));
        if (j == null || j.optString("title").isEmpty()) return null;
        i = new Item(l.kind); i.title = j.optString("title");
        return i;
    }

    /**
     * A Spotify page's tags. og:title is the name ("Blinding Lights", "The Weeknd"), though an album's
     * is "Listen to After Hours - Album by The Weeknd | Spotify"; the page title has the artist
     * ("Blinding Lights - song and lyrics by The Weeknd | Spotify"); the description has the rest
     * ("The Weeknd · After Hours · Song · 2020", "Podcast Episode · The Daily · …").
     */
    static Item spotifyPage(Kind kind, String html) {
        Map<String, String> meta = metaTags(html);
        String title = pageTitle(html), og = meta.getOrDefault("og:title", ""), desc = meta.getOrDefault("og:description", "");
        Item i = new Item(kind);
        Pattern byArtist = Pattern.compile("^(?:Listen to )?(.+) - (?:Album|Single|EP|Compilation|(?i:song)\\b[^|]*?) by (.+?) \\| Spotify$");
        Matcher m;
        String name = og.isEmpty() ? title : og;
        if ((m = byArtist.matcher(name)).find()) { name = m.group(1); i.artist = m.group(2).trim(); }
        else if ((m = byArtist.matcher(title)).find()) i.artist = m.group(2).trim();
        name = name.replaceFirst("^Listen to ", "").replaceFirst("\\s*\\|[^|]*Spotify$", "").trim();
        if (kind == Kind.ARTIST) name = name.replaceFirst("\\s+Songs$", "");
        i.title = name;
        String musician = meta.getOrDefault("music:musician_description", "");
        if (!musician.isEmpty() && (kind == Kind.SONG || kind == Kind.ALBUM)) i.artist = musician;
        String[] parts = desc.replaceFirst("^Listen to .*? on Spotify\\.\\s*", "").split("\\s+·\\s+");
        if (i.artist.isEmpty() && (kind == Kind.SONG || kind == Kind.ALBUM) && parts.length >= 2)
            i.artist = parts[0].matches("(?i)song|album|single|ep") ? parts[1] : parts[0];
        if (kind == Kind.SONG) {
            if (parts.length >= 4 && parts[2].equalsIgnoreCase("Song")) i.album = parts[1];
            try { i.ms = Long.parseLong(meta.getOrDefault("music:duration", "0")) * 1000; } catch (Exception ignored) {}
        }
        if (kind == Kind.EPISODE) {
            if ((m = Pattern.compile("Listen to this episode from (.+?) on Spotify").matcher(desc)).find()) i.show = m.group(1).trim();
            else if (parts.length >= 2 && parts[0].toLowerCase(Locale.ROOT).startsWith("podcast")) i.show = parts[1];
        }
        return i.title.isEmpty() ? null : i;
    }

    private static Item youtube(Link l, Net net) throws Exception {
        String watch = l.kind == Kind.SONG ? "https://www.youtube.com/watch?v=" + l.id : l.kind == Kind.ALBUM && l.id.startsWith("OLAK") ? "https://www.youtube.com/playlist?list=" + l.id : "";
        if (!watch.isEmpty()) {
            JSONObject j = json(net.get("https://www.youtube.com/oembed?format=json&url=" + enc(watch), false));
            if (j != null && !j.optString("title").isEmpty()) {
                String channel = j.optString("author_name").replaceFirst("\\s+-\\s+Topic$", "").replaceFirst("(?i)VEVO$", "").trim();
                String t = j.optString("title").replaceFirst("^(?i)Album\\s+-\\s+", "")
                    .replaceAll("(?i)\\s*[\\(\\[](official[^\\)\\]]*|lyrics?[^\\)\\]]*|audio|video|music video|visuali[sz]er|hd|4k)[\\)\\]]", "").trim();
                Item i = new Item(l.kind); i.title = t; i.artist = channel;
                String[] parts = t.split("\\s+-\\s+", 2);
                if (parts.length == 2) { i.title = parts[1].trim(); if (channel.isEmpty() || !norm(parts[0]).equals(norm(channel))) i.artist = parts[0].trim(); }
                return i;
            }
        }
        return page(l, net);
    }

    /** Any other link: the page's own title, like "Blinding Lights by The Weeknd on TIDAL". */
    private static Item page(Link l, Net net) throws Exception {
        String html = net.get(l.url, true);
        if (html == null) return null;
        Map<String, String> meta = metaTags(html);
        String og = meta.getOrDefault("og:title", ""), tag = pageTitle(html);
        String title = !og.isEmpty() && !(tag.toLowerCase(Locale.ROOT).contains(" by ") && !og.toLowerCase(Locale.ROOT).contains(" by ")) ? og : tag;
        note("page " + l.app + " title=" + pageTitle(html) + " | og:title=" + meta.get("og:title") + " | og:description=" + meta.get("og:description"));
        return fromTitle(l.kind, title, meta.getOrDefault("og:description", ""), meta.getOrDefault("music:musician_description", ""));
    }

    static Item fromTitle(Kind kind, String title, String description, String artist) {
        String t = title == null ? "" : title.trim();
        for (int n = 0; n < 2; n++)
            t = t.replaceFirst("(?i)\\s*(?:[|\\-–—]\\s*)?(?:on\\s+|listen (?:to it )?on\\s+)?(?:TIDAL|Amazon Music|Amazon\\.com|SoundCloud|Deezer|Spotify|YouTube Music|Pocket Casts|Apple Music)\\s*$", "").trim();
        t = t.replaceFirst("(?i)^(?:play|listen to|stream)\\s+", "").replaceFirst("(?i)\\s+(?:free online|online)$", "").trim();
        Item i = new Item(kind == null ? Kind.SONG : kind);
        i.artist = artist == null ? "" : artist.trim();
        if (kind == Kind.SONG || kind == Kind.ALBUM) {
            int by = t.toLowerCase(Locale.ROOT).lastIndexOf(" by ");
            if (by > 0) { if (i.artist.isEmpty()) i.artist = t.substring(by + 4).trim(); t = t.substring(0, by).trim(); }
            t = t.replaceFirst("(?i)\\s+-\\s+(?:song|single|ep|album)$", "");
        }
        if (kind == Kind.EPISODE && description != null) {
            String[] parts = t.split("\\s+[|–—-]\\s+", 2);
            if (parts.length == 2) { t = parts[0]; i.show = parts[1]; }
        }
        i.title = t;
        return t.isEmpty() ? null : i;
    }

    // ---------------------------------------------------------------- finding it in your app

    /** Where to open it in the app, or null when there's no way to (a playlist, or nothing to search for). */
    static Match find(Item item, App target, Net net, String country) {
        if (item == null || target == null) return null;
        String cc = country == null || country.length() != 2 ? "us" : country.toLowerCase(Locale.ROOT);
        try {
            if (item.kind.podcast()) return podcast(item, target, net, cc);
            if (item.kind == Kind.PLAYLIST || !target.music) return null;
            String url = null;
            switch (target) {
                case APPLE_MUSIC: url = appleFind(item, net, cc); break;
                case DEEZER: url = deezerFind(item, net); break;
                case YOUTUBE_MUSIC: url = youtubeFind(item, net, cc); if (url == null) url = brainzFind(item, target, net); break;
                default: url = brainzFind(item, target, net); break;
            }
            if (url != null) return new Match(url, "", "exact");
        } catch (Exception e) { note("find " + target + ": " + e); }
        return search(item, target);
    }

    /** The app's own search, with the name in it. */
    static Match search(Item item, App app) {
        String q = searchWords(item);
        if (q.isEmpty()) return null;
        String e = enc(q), url;
        switch (app) {
            case SPOTIFY: return new Match("https://open.spotify.com/search/" + e, "spotify:search:" + e, "search");
            case APPLE_MUSIC: url = "https://music.apple.com/us/search?term=" + e; break;
            case YOUTUBE_MUSIC: url = "https://music.youtube.com/search?q=" + e; break;
            case AMAZON_MUSIC: url = "https://music.amazon.com/search/" + e; break;
            case TIDAL: url = "https://tidal.com/search?q=" + e; break;
            case DEEZER: url = "https://www.deezer.com/search/" + e; break;
            case SOUNDCLOUD: url = "https://soundcloud.com/search?q=" + e; break;
            case APPLE_PODCASTS: url = "https://podcasts.apple.com/us/search?term=" + e; break;
            default: return null;
        }
        return new Match(url, "", "search");
    }

    static String searchWords(Item i) {
        switch (i.kind) {
            case SONG: case ALBUM: return (core(i.title, false) + " " + firstArtist(i.artist)).trim();
            case EPISODE: return (i.title + " " + i.show).trim();
            default: return i.title.trim();
        }
    }

    private static String appleFind(Item item, Net net, String cc) throws Exception {
        String entity = item.kind == Kind.SONG ? "song" : item.kind == Kind.ALBUM ? "album" : "musicArtist";
        JSONObject j = json(net.get(ITUNES + "search?media=music&limit=15&country=" + cc + "&entity=" + entity + "&term=" + enc(searchWords(item)), false));
        JSONArray r = j == null ? null : j.optJSONArray("results");
        if (r == null) return null;
        JSONObject best = null; long bestGap = Long.MAX_VALUE;
        for (int n = 0; n < r.length(); n++) {
            JSONObject o = r.optJSONObject(n); Item found = fromItunes(o);
            if (found == null || found.kind != item.kind || !same(found, item)) continue;
            long gap = gap(found.ms, item.ms);
            if (gap < bestGap) { best = o; bestGap = gap; }
        }
        if (best == null) return null;
        return clean(best.optString(item.kind == Kind.SONG ? "trackViewUrl" : item.kind == Kind.ALBUM ? "collectionViewUrl" : "artistLinkUrl"));
    }

    private static String deezerFind(Item item, Net net) throws Exception {
        if (item.kind == Kind.SONG && !item.isrc.isEmpty()) {
            JSONObject j = json(net.get(DEEZER + "track/isrc:" + item.isrc, false));
            if (j != null && !j.has("error") && !j.optString("link").isEmpty()) return j.optString("link");
        }
        if (item.kind == Kind.ALBUM && !item.upc.isEmpty()) {
            JSONObject j = json(net.get(DEEZER + "album/upc:" + item.upc, false));
            if (j != null && !j.has("error") && !j.optString("link").isEmpty()) return j.optString("link");
        }
        JSONObject hit = deezerSearch(item, net);
        return hit == null ? null : hit.optString("link", null);
    }

    /** The same song (album, artist) in Deezer's search, or null. */
    private static JSONObject deezerSearch(Item item, Net net) throws Exception {
        String path, q;
        String t = core(item.title, false).replace("\"", " "), a = firstArtist(item.artist).replace("\"", " ");
        switch (item.kind) {
            case SONG: path = "search/track"; q = a.isEmpty() ? "track:\"" + t + "\"" : "artist:\"" + a + "\" track:\"" + t + "\""; break;
            case ALBUM: path = "search/album"; q = a.isEmpty() ? "album:\"" + t + "\"" : "artist:\"" + a + "\" album:\"" + t + "\""; break;
            case ARTIST: path = "search/artist"; q = t; break;
            default: return null;
        }
        String reply = net.get(DEEZER + path + "?limit=15&q=" + enc(q), false);
        JSONObject j = json(reply);
        JSONArray d = j == null ? null : j.optJSONArray("data");
        note("deezer search " + q + " → " + (reply == null ? "no reply" : d == null ? reply.substring(0, Math.min(200, reply.length())) : d.length() + " results"));
        if ((d == null || d.length() == 0) && item.kind != Kind.ARTIST) {
            String plain = searchWords(item);
            reply = net.get(DEEZER + path + "?limit=15&q=" + enc(plain), false);
            j = json(reply); d = j == null ? null : j.optJSONArray("data");
            note("deezer search " + plain + " → " + (reply == null ? "no reply" : d == null ? reply.substring(0, Math.min(200, reply.length())) : d.length() + " results"));
        }
        if (d == null) return null;
        JSONObject best = null; long bestGap = Long.MAX_VALUE;
        for (int n = 0; n < d.length(); n++) {
            JSONObject o = d.optJSONObject(n); if (o == null) continue;
            Item found = new Item(item.kind);
            found.title = item.kind == Kind.ARTIST ? o.optString("name") : o.optString("title");
            found.artist = name(o.optJSONObject("artist"));
            found.ms = o.optLong("duration") * 1000;
            if (!same(found, item)) continue;
            long gap = gap(found.ms, item.ms);
            if (gap < bestGap) { best = o; bestGap = gap; }
        }
        return best;
    }

    /** Adds the song's ISRC (the album's barcode) from Deezer, so MusicBrainz can look it up exactly. */
    static void addCodes(Item item, Net net) {
        try {
            if (item.kind == Kind.SONG && item.isrc.isEmpty()) {
                JSONObject hit = deezerSearch(item, net);
                if (hit != null) { JSONObject t = json(net.get(DEEZER + "track/" + hit.optLong("id"), false)); if (t != null) item.isrc = t.optString("isrc"); }
            } else if (item.kind == Kind.ALBUM && item.upc.isEmpty()) {
                JSONObject hit = deezerSearch(item, net);
                if (hit != null) { JSONObject a = json(net.get(DEEZER + "album/" + hit.optLong("id"), false)); if (a != null) item.upc = a.optString("upc"); }
            }
        } catch (Exception e) { note("codes: " + e); }
        note("codes " + item);
    }

    // MusicBrainz asks apps to send at most one request a second.
    private static long lastBrainz;
    static long brainzGap = 1100; // tests: 0
    private static String brainz(Net net, String path) throws Exception {
        synchronized (MusicLinks.class) {
            long wait = lastBrainz + brainzGap - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastBrainz = System.currentTimeMillis();
        }
        String url = MB + path + (path.contains("?") ? "&" : "?") + "fmt=json";
        String reply = net.get(url, false);
        note("musicbrainz " + path + " → " + (reply == null ? "nothing" : reply.length() + " chars"));
        return reply;
    }

    /** The link MusicBrainz has for this song (album, artist) on that service, or null. */
    private static String brainzFind(Item item, App target, Net net) throws Exception {
        addCodes(item, net);
        switch (item.kind) {
            case SONG: {
                String recording = null;
                if (!item.isrc.isEmpty()) {
                    JSONObject j = json(brainz(net, "isrc/" + item.isrc));
                    JSONArray r = j == null ? null : j.optJSONArray("recordings");
                    if (r != null) for (int n = 0; n < r.length() && recording == null; n++) {
                        JSONObject o = r.optJSONObject(n);
                        if (o != null && (r.length() == 1 || sameName(o.optString("title"), item.title))) recording = o.optString("id");
                    }
                }
                if (recording == null) {
                    String q = "recording:\"" + core(item.title, false).replace("\"", " ") + "\"" + (item.artist.isEmpty() ? "" : " AND artist:\"" + firstArtist(item.artist).replace("\"", " ") + "\"");
                    JSONObject j = json(brainz(net, "recording?limit=15&query=" + enc(q)));
                    recording = bestRecording(j == null ? null : j.optJSONArray("recordings"), item);
                }
                note("recording " + recording);
                if (recording == null) return null;
                JSONObject rec = json(brainz(net, "recording/" + recording + "?inc=url-rels+releases"));
                String direct = linkFor(rec, target, Kind.SONG);
                if (direct != null || target != App.SPOTIFY) return direct;
                // Spotify links are mostly filed under the album: find the song in that album's track list.
                JSONArray releases = rec == null ? null : rec.optJSONArray("releases");
                if (releases == null) return null;
                List<JSONObject> order = new ArrayList<>();
                for (int n = 0; n < releases.length(); n++) {
                    JSONObject o = releases.optJSONObject(n); if (o == null) continue;
                    if (!item.album.isEmpty() && sameName(o.optString("title"), item.album)) order.add(0, o); else order.add(o);
                }
                for (int n = 0; n < Math.min(3, order.size()); n++) {
                    JSONObject rel = json(brainz(net, "release/" + order.get(n).optString("id") + "?inc=url-rels"));
                    String album = linkFor(rel, App.SPOTIFY, Kind.ALBUM);
                    if (album == null) continue;
                    String track = spotifyTrackOnAlbum(album, item, net);
                    if (track != null) return track;
                }
                return null;
            }
            case ALBUM: {
                String release = null;
                if (!item.upc.isEmpty()) {
                    JSONObject j = json(brainz(net, "release?limit=3&query=" + enc("barcode:" + item.upc)));
                    JSONArray r = j == null ? null : j.optJSONArray("releases");
                    if (r != null && r.length() > 0) release = r.optJSONObject(0).optString("id");
                }
                if (release == null) {
                    String q = "release:\"" + core(item.title, false).replace("\"", " ") + "\"" + (item.artist.isEmpty() ? "" : " AND artist:\"" + firstArtist(item.artist).replace("\"", " ") + "\"");
                    JSONObject j = json(brainz(net, "release?limit=8&query=" + enc(q)));
                    JSONArray r = j == null ? null : j.optJSONArray("releases");
                    // Of the matching releases (an album has many: countries, formats), the first with a link to this app.
                    if (r != null) for (int n = 0; n < r.length(); n++) {
                        JSONObject o = r.optJSONObject(n);
                        if (o == null || !sameName(o.optString("title"), item.title) || !sameArtist(credit(o), item.artist)) continue;
                        String link = linkFor(json(brainz(net, "release/" + o.optString("id") + "?inc=url-rels")), target, Kind.ALBUM);
                        if (link != null) return link;
                        if (n >= 3) break;
                    }
                    return null;
                }
                return linkFor(json(brainz(net, "release/" + release + "?inc=url-rels")), target, Kind.ALBUM);
            }
            case ARTIST: {
                JSONObject j = json(brainz(net, "artist?limit=5&query=" + enc("artist:\"" + item.title.replace("\"", " ") + "\"")));
                JSONArray r = j == null ? null : j.optJSONArray("artists");
                if (r != null) for (int n = 0; n < r.length(); n++) {
                    JSONObject o = r.optJSONObject(n);
                    if (o != null && sameName(o.optString("name"), item.title)) return linkFor(json(brainz(net, "artist/" + o.optString("id") + "?inc=url-rels")), target, Kind.ARTIST);
                }
                return null;
            }
            default: return null;
        }
    }

    /**
     * The recording a store means, of MusicBrainz's many by that name (live ones, remixes, covers):
     * the right title and artist, about the same length, not live or remixed unless the name says
     * so, and the one on the most releases (the original is on the album, singles, and compilations).
     */
    static String bestRecording(JSONArray r, Item item) {
        if (r == null) return null;
        String best = null; long bestScore = Long.MIN_VALUE;
        boolean wantsVersion = item.title.toLowerCase(Locale.ROOT).matches(".*\\b(live|remix|mix|acoustic|demo|version|edit)\\b.*");
        for (int n = 0; n < r.length(); n++) {
            JSONObject o = r.optJSONObject(n);
            if (o == null || !sameName(o.optString("title"), item.title) || !sameArtist(credit(o), item.artist)) continue;
            long score = 0;
            JSONArray releases = o.optJSONArray("releases"); score += Math.min(60, releases == null ? 0 : releases.length());
            if (o.optJSONArray("isrcs") != null && o.optJSONArray("isrcs").length() > 0) score += 25;
            long length = o.optLong("length");
            if (item.ms > 0 && length > 0) score += Math.abs(length - item.ms) <= 3000 ? 60 : Math.abs(length - item.ms) <= 10000 ? 20 : -40;
            if (!wantsVersion && (o.optBoolean("video") || o.optString("disambiguation").toLowerCase(Locale.ROOT).matches(".*\\b(live|remix|mix|acoustic|demo|instrumental|karaoke)\\b.*"))) score -= 80;
            if (score > bestScore) { bestScore = score; best = o.optString("id"); }
        }
        return best;
    }

    private static String credit(JSONObject o) {
        JSONArray c = o.optJSONArray("artist-credit");
        StringBuilder b = new StringBuilder();
        if (c != null) for (int n = 0; n < c.length(); n++) { JSONObject a = c.optJSONObject(n); if (a != null) b.append(a.optString("name")).append(a.optString("joinphrase")); }
        return b.toString();
    }

    /** Of a MusicBrainz entry's links, the one for this app and kind. */
    static String linkFor(JSONObject entry, App app, Kind kind) {
        JSONArray rels = entry == null ? null : entry.optJSONArray("relations");
        if (rels == null) return null;
        for (int n = 0; n < rels.length(); n++) {
            JSONObject rel = rels.optJSONObject(n); JSONObject url = rel == null ? null : rel.optJSONObject("url");
            String res = url == null ? "" : url.optString("resource");
            Link l = parse(res);
            if (l != null && l.app == app && l.kind == kind) return l.app == App.SPOTIFY || l.app == App.TIDAL ? res.replaceFirst("\\?.*$", "") : res;
        }
        return null;
    }

    /** The song on a Spotify album, from the album's page: each song is listed there with its link. */
    static String spotifyTrackOnAlbum(String albumUrl, Item item, Net net) throws Exception {
        Link album = parse(albumUrl);
        if (album == null || album.kind != Kind.ALBUM) return null;
        String embed = net.get("https://open.spotify.com/embed/album/" + album.id, true);
        if (embed != null) {
            // The embed's track list: {"uri":"spotify:track:…","title":"…",…} in some order.
            Matcher m = Pattern.compile("\\{[^{}]*\"spotify:track:([A-Za-z0-9]{22})\"[^{}]*\\}").matcher(embed);
            while (m.find()) {
                Matcher t = Pattern.compile("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(m.group());
                if (t.find() && sameName(unescapeJson(t.group(1)), item.title)) return "https://open.spotify.com/track/" + m.group(1);
            }
            note("embed: no track named " + item.title + " in " + embed.length() + " chars");
        }
        String page = net.get("https://open.spotify.com/album/" + album.id, true);
        if (page == null) return null;
        // The album page's own tags list its songs in order; the song's place comes from its title in the page text.
        List<String> songs = new ArrayList<>();
        Matcher m = Pattern.compile("<meta[^>]+(?:name|property)=\"music:song\"[^>]+content=\"https://open\\.spotify\\.com/track/([A-Za-z0-9]{22})\"").matcher(page);
        while (m.find()) songs.add(m.group(1));
        note("album page: " + songs.size() + " songs tagged");
        return null;
    }

    private static String unescapeJson(String s) { try { return new JSONObject("{\"v\":\"" + s + "\"}").getString("v"); } catch (Exception e) { return s; } }

    /** YouTube Music's own search (what its website uses), for the same song, album, or artist. */
    private static String youtubeFind(Item item, Net net, String cc) throws Exception {
        String filter = item.kind == Kind.SONG ? "EgWKAQIIAWoMEA4QChADEAQQCRAF" : item.kind == Kind.ALBUM ? "EgWKAQIYAWoMEA4QChADEAQQCRAF" : item.kind == Kind.ARTIST ? "EgWKAQIgAWoMEA4QChADEAQQCRAF" : null;
        if (filter == null) return null;
        JSONObject client = new JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", YTM_VERSION).put("hl", "en").put("gl", cc.toUpperCase(Locale.ROOT));
        JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client)).put("query", searchWords(item)).put("params", filter);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", "https://music.youtube.com"); headers.put("Referer", "https://music.youtube.com/");
        headers.put("X-YouTube-Client-Name", "67"); headers.put("X-YouTube-Client-Version", YTM_VERSION);
        String reply = net.post("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", body.toString(), headers);
        JSONObject root = json(reply);
        if (root == null) { note("youtube: no reply"); return null; }
        return youtubeResult(root, item);
    }

    static final String YTM_VERSION = "1.20251103.01.00";

    /** The first search result that is the same song (album, artist). */
    static String youtubeResult(JSONObject root, Item item) {
        List<JSONObject> rows = new ArrayList<>();
        collect(root, "musicResponsiveListItemRenderer", rows);
        for (JSONObject r : rows) {
            JSONArray cols = r.optJSONArray("flexColumns");
            if (cols == null || cols.length() == 0) continue;
            String title = runs(cols.optJSONObject(0)), sub = cols.length() > 1 ? runs(cols.optJSONObject(1)) : "";
            JSONObject browse = r.optJSONObject("navigationEndpoint") == null ? null : r.optJSONObject("navigationEndpoint").optJSONObject("browseEndpoint");
            String browseId = browse == null ? "" : browse.optString("browseId");
            switch (item.kind) {
                case SONG: {
                    if (!sameName(title, item.title) || !sameArtist(sub, item.artist)) continue;
                    JSONObject data = r.optJSONObject("playlistItemData");
                    String v = data != null ? data.optString("videoId") : "";
                    if (v.isEmpty()) v = firstString(r, "videoId");
                    if (v != null && !v.isEmpty()) return "https://music.youtube.com/watch?v=" + v;
                    break;
                }
                case ALBUM:
                    if (browseId.startsWith("MPRE") && sameName(title, item.title) && sameArtist(sub, item.artist)) return "https://music.youtube.com/browse/" + browseId;
                    break;
                case ARTIST:
                    if (browseId.startsWith("UC") && sameName(title, item.title)) return "https://music.youtube.com/channel/" + browseId;
                    break;
                default: return null;
            }
        }
        note("youtube: " + rows.size() + " results, none the same");
        return null;
    }

    private static String runs(JSONObject column) {
        JSONObject c = column == null ? null : column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        JSONObject text = c == null ? null : c.optJSONObject("text");
        JSONArray runs = text == null ? null : text.optJSONArray("runs");
        StringBuilder b = new StringBuilder();
        if (runs != null) for (int n = 0; n < runs.length(); n++) { JSONObject run = runs.optJSONObject(n); if (run != null) b.append(run.optString("text")); }
        return b.toString();
    }

    private static void collect(Object o, String key, List<JSONObject> out) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next(); Object v = j.opt(k);
                if (k.equals(key) && v instanceof JSONObject) out.add((JSONObject) v); else collect(v, key, out);
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int n = 0; n < a.length(); n++) collect(a.opt(n), key, out);
        }
    }

    private static String firstString(Object o, String key) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Object v = j.opt(key);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) { String r = firstString(j.opt(it.next()), key); if (r != null) return r; }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int n = 0; n < a.length(); n++) { String r = firstString(a.opt(n), key); if (r != null) return r; }
        }
        return null;
    }

    // ---------------------------------------------------------------- podcasts

    private static Match podcast(Item item, App target, Net net, String cc) throws Exception {
        if (!target.podcasts) return null;
        String showName = item.kind == Kind.SHOW ? item.title : item.show;
        if (item.appleShow.isEmpty() && !showName.isEmpty()) {
            JSONObject j = json(net.get(ITUNES + "search?media=podcast&entity=podcast&limit=10&country=" + cc + "&term=" + enc(showName), false));
            JSONArray r = j == null ? null : j.optJSONArray("results");
            if (r != null) for (int n = 0; n < r.length(); n++) {
                Item found = fromItunes(r.optJSONObject(n));
                if (found != null && sameName(found.title, showName)) { item.appleShow = found.appleShow; item.appleShowUrl = found.appleShowUrl; if (item.feed.isEmpty()) item.feed = found.feed; break; }
            }
        }
        note("podcast " + item + " apple " + item.appleShow + " feed " + item.feed);
        boolean episode = item.kind == Kind.EPISODE;
        switch (target) {
            case APPLE_PODCASTS: {
                if (item.appleShow.isEmpty()) return search(item, target);
                if (episode && !item.title.isEmpty()) {
                    JSONObject j = json(net.get(ITUNES + "lookup?id=" + item.appleShow + "&entity=podcastEpisode&limit=200&country=" + cc, false));
                    JSONArray r = j == null ? null : j.optJSONArray("results");
                    if (r != null) for (int n = 1; n < r.length(); n++) {
                        JSONObject e = r.optJSONObject(n);
                        if (e != null && sameName(e.optString("trackName"), item.title)) return new Match(clean(e.optString("trackViewUrl")), "", "exact");
                    }
                }
                String show = item.appleShowUrl.isEmpty() ? "https://podcasts.apple.com/" + cc + "/podcast/id" + item.appleShow : item.appleShowUrl;
                return new Match(show, "", episode ? "show" : "exact");
            }
            case POCKET_CASTS:
                if (!item.appleShow.isEmpty()) return new Match("https://pca.st/itunes/" + item.appleShow, item.feed.isEmpty() ? "" : "pktc://subscribe/" + bare(item.feed), episode ? "show" : "exact");
                return item.feed.isEmpty() ? null : new Match("pktc://subscribe/" + bare(item.feed), "", episode ? "show" : "exact");
            case ANTENNAPOD:
                return item.feed.isEmpty() ? null : new Match("antennapod-subscribe://" + bare(item.feed), "https://antennapod.org/deeplink/subscribe?url=" + enc(item.feed) + "&title=" + enc(showName), episode ? "show" : "exact");
            case PODCAST_ADDICT:
                return item.feed.isEmpty() ? null : new Match("podcastaddict://" + bare(item.feed), "pcast://" + bare(item.feed), episode ? "show" : "exact");
            default:
                return search(item, target);
        }
    }

    private static String bare(String feed) { return feed.replaceFirst("(?i)^https?://", ""); }

    // ---------------------------------------------------------------- names

    /** Lowercase letters and digits only: "Beyoncé — Halo!" and "beyonce halo" are the same. */
    static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        t = t.replace("&", " and ").replaceAll("[’'`´]", "");
        return t.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** The name without what stores add to it: "(feat. X)", "[Remastered]", " - 2011 Remaster", " - Single". */
    static String core(String s, boolean normalize) {
        String t = s == null ? "" : s;
        t = t.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]", " ");
        t = t.replaceAll("(?i)\\s+[-–—]\\s+[^-–—]*\\b(remaster(ed)?|version|edit|mix|live|mono|stereo|single|ep|deluxe|anniversary|acoustic|radio|explicit|clean|bonus|from)\\b.*$", "");
        t = t.replaceAll("(?i)\\s+(feat\\.?|ft\\.?|featuring|with)\\s+.*$", "").trim();
        if (t.isEmpty()) t = s == null ? "" : s;
        return normalize ? norm(t) : t.trim();
    }

    static boolean sameName(String a, String b) {
        String x = core(a, true), y = core(b, true);
        return !x.isEmpty() && (x.equals(y) || norm(a).equals(norm(b)));
    }

    /** found names the wanted artist (or, when it's a group credit, its first artist). */
    static boolean sameArtist(String found, String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return true;
        String f = " " + norm(found) + " ", w = norm(firstArtist(wanted));
        return !w.isEmpty() && f.contains(" " + w + " ");
    }

    static String firstArtist(String a) {
        if (a == null) return "";
        String[] parts = a.split("(?i)\\s*(?:,|;|\\s&\\s|\\sx\\s|\\sfeat\\.?\\s|\\sft\\.?\\s|\\sfeaturing\\s|\\swith\\s|\\s/\\s)\\s*");
        return parts.length == 0 ? a.trim() : parts[0].trim();
    }

    private static boolean same(Item found, Item wanted) {
        if (wanted.kind == Kind.ARTIST) return sameName(found.title, wanted.title);
        return sameName(found.title, wanted.title) && sameArtist(found.artist, wanted.artist);
    }

    /** How far apart two lengths are; an unknown length is neither close nor far. */
    private static long gap(long a, long b) { return a <= 0 || b <= 0 ? 60_000 : Math.abs(a - b); }

    static String albumName(String s) { return s == null ? "" : s.replaceFirst("\\s+-\\s+(?:Single|EP)$", ""); }

    /** Apple's links without their tracking tag ("uo=4"). */
    private static String clean(String url) {
        if (url == null) return "";
        return url.replaceAll("([?&])uo=\\d+&?", "$1").replaceFirst("[?&]$", "");
    }

    // ---------------------------------------------------------------- pages and JSON

    static JSONObject json(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.startsWith("{")) return null;
        try { return new JSONObject(t); } catch (Exception e) { return null; }
    }

    static String pageTitle(String html) {
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        return m.find() ? unescape(m.group(1)).replaceAll("\\s+", " ").trim() : "";
    }

    /** A page's &lt;meta property/name="…" content="…"&gt; tags (the first of each). */
    static Map<String, String> metaTags(String html) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?is)<meta\\s[^>]*>").matcher(html);
        Pattern key = Pattern.compile("(?is)(?:property|name)\\s*=\\s*([\"'])(.*?)\\1"), value = Pattern.compile("(?is)content\\s*=\\s*([\"'])(.*?)\\1");
        while (m.find()) {
            Matcher k = key.matcher(m.group()), v = value.matcher(m.group());
            if (k.find() && v.find()) out.putIfAbsent(k.group(2).toLowerCase(Locale.ROOT), unescape(v.group(2)).trim());
        }
        return out;
    }

    static String unescape(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            int c; try { c = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16); } catch (Exception e) { c = '?'; }
            m.appendReplacement(b, Matcher.quoteReplacement(new String(Character.toChars(c))));
        }
        m.appendTail(b);
        return b.toString().replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    // ---------------------------------------------------------------- the real connection

    /** The web, as this app reaches it (and the CI probe). */
    static final class Http implements Net {
        private final String app;
        /** app: who's asking, for MusicBrainz ("MessageCenter/1.5.40 ( https://github.com/bags156/message-center )"). */
        Http(String app) { this.app = app; }

        @Override public String get(String url, boolean page) throws Exception {
            for (int attempt = 0; ; attempt++) {
                HttpURLConnection c = open(url, page);
                try {
                    int code = c.getResponseCode();
                    if ((code == 503 || code == 429) && attempt < 2) { note("get " + code + " (busy, again) " + url); Thread.sleep(1500L * (attempt + 1)); continue; }
                    if (code >= 400) { note("get " + code + " " + url); return null; }
                    return read(c, page ? 900 * 1024 : 3 * 1024 * 1024);
                } finally { c.disconnect(); }
            }
        }

        @Override public String post(String url, String json, Map<String, String> headers) throws Exception {
            HttpURLConnection c = open(url, false);
            try {
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("User-Agent", BROWSER);
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                try (OutputStream out = c.getOutputStream()) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                if (code >= 400) { note("post " + code + " " + url); return null; }
                return read(c, 4 * 1024 * 1024);
            } finally { c.disconnect(); }
        }

        @Override public String follow(String url) throws Exception {
            String at = url;
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c = open(at, true);
                c.setInstanceFollowRedirects(false);
                try {
                    int code = c.getResponseCode();
                    String to = c.getHeaderField("Location");
                    if (code / 100 != 3 || to == null) return at;
                    at = URI.create(at).resolve(to).toString();
                } finally { c.disconnect(); }
            }
            return at;
        }

        private HttpURLConnection open(String url, boolean page) throws Exception {
            if (!url.startsWith("https://")) throw new java.io.IOException("Only encrypted connections");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(7000); c.setReadTimeout(10000); c.setInstanceFollowRedirects(true);
            // Pages give their names to messaging apps' link previews; APIs get told which app is asking.
            c.setRequestProperty("User-Agent", page ? "WhatsApp/2 (compatible; MessageCenter link preview; +https://github.com/bags156/message-center)" : app);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
            c.setRequestProperty("Accept", page ? "text/html,application/xhtml+xml" : "application/json");
            return c;
        }

        private static String read(HttpURLConnection c, int max) throws Exception {
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0 && b.size() < max) b.write(buf, 0, Math.min(n, max - b.size()));
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        private static final String BROWSER = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    }
}
