package io.github.bags156.messagecenter;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MusicLinksTest {
    static { MusicLinks.brainzGap = 0; }
    /** Answers from saved responses: the first saved address the request starts with. */
    static final class Fake implements MusicLinks.Net {
        final Map<String, String> pages = new LinkedHashMap<>();
        final List<String> asked = new ArrayList<>();
        String posted;
        Fake on(String prefix, String body) { pages.put(prefix, body); return this; }
        Fake on(String prefix, JSONObject body) { return on(prefix, body.toString()); }
        @Override public String get(String url, boolean page) { asked.add(url); for (Map.Entry<String, String> e : pages.entrySet()) if (url.startsWith(e.getKey())) return e.getValue(); return null; }
        @Override public String post(String url, String json, Map<String, String> headers) { asked.add(url); posted = json; return get(url, false); }
        @Override public String follow(String url) { String to = pages.get("follow:" + url); return to == null ? url : to; }
    }

    private static JSONObject o(Object... kv) throws Exception { JSONObject j = new JSONObject(); for (int i = 0; i < kv.length; i += 2) j.put((String) kv[i], kv[i + 1]); return j; }
    private static JSONArray a(Object... items) { JSONArray j = new JSONArray(); for (Object i : items) j.put(i); return j; }

    @Test public void readsEachServicesLinks() {
        MusicLinks.Link l = MusicLinks.parse("https://open.spotify.com/intl-de/track/0VjIjW4GlUZAMYd2vXMi3b?si=abc");
        assertEquals(MusicLinks.App.SPOTIFY, l.app); assertEquals(MusicLinks.Kind.SONG, l.kind); assertEquals("0VjIjW4GlUZAMYd2vXMi3b", l.id);
        assertEquals(MusicLinks.Kind.EPISODE, MusicLinks.parse("https://open.spotify.com/episode/abc").kind);
        assertTrue(MusicLinks.parse("https://spotify.link/xyz").isShort());

        l = MusicLinks.parse("https://music.apple.com/us/album/blinding-lights/1488408555?i=1488408568");
        assertEquals(MusicLinks.Kind.SONG, l.kind); assertEquals("1488408568", l.id); assertEquals("us", l.country); assertEquals("1488408555", l.parent);
        assertEquals(MusicLinks.Kind.ALBUM, MusicLinks.parse("https://music.apple.com/gb/album/after-hours/1499378108").kind);
        assertEquals("1499378108", MusicLinks.parse("https://itunes.apple.com/us/album/after-hours/id1499378108").id);
        assertEquals(MusicLinks.Kind.ARTIST, MusicLinks.parse("https://music.apple.com/us/artist/the-weeknd/479756766").kind);
        assertEquals(MusicLinks.Kind.SONG, MusicLinks.parse("https://music.apple.com/us/song/blinding-lights/1488408568").kind);

        l = MusicLinks.parse("https://podcasts.apple.com/us/podcast/the-daily/id1200361736?i=1000600000000");
        assertEquals(MusicLinks.App.APPLE_PODCASTS, l.app); assertEquals(MusicLinks.Kind.EPISODE, l.kind); assertEquals("1200361736", l.parent);
        assertEquals(MusicLinks.Kind.SHOW, MusicLinks.parse("https://podcasts.apple.com/us/podcast/the-daily/id1200361736").kind);

        assertEquals(MusicLinks.Kind.SONG, MusicLinks.parse("https://music.youtube.com/watch?v=4NRXx6U8ABQ&si=x").kind);
        assertEquals(MusicLinks.Kind.ALBUM, MusicLinks.parse("https://music.youtube.com/playlist?list=OLAK5uy_abc").kind);
        // "PL…" is a podcast or anyone's playlist: which is found out from its page (a playlist is then left alone).
        assertEquals(MusicLinks.Kind.SHOW, MusicLinks.parse("https://music.youtube.com/playlist?list=PLabc").kind);
        assertEquals(MusicLinks.Kind.PLAYLIST, MusicLinks.parse("https://music.youtube.com/playlist?list=RDCLAK5uy_abc").kind);
        assertEquals(MusicLinks.Kind.ALBUM, MusicLinks.parse("https://music.youtube.com/browse/MPREb_abc").kind);
        assertNull("a YouTube video isn't a song", MusicLinks.parse("https://www.youtube.com/watch?v=4NRXx6U8ABQ"));

        assertEquals(MusicLinks.Kind.SONG, MusicLinks.parse("https://tidal.com/browse/track/134858527").kind);
        assertEquals(MusicLinks.Kind.ALBUM, MusicLinks.parse("https://listen.tidal.com/album/134858522").kind);
        assertEquals(MusicLinks.Kind.SONG, MusicLinks.parse("https://www.deezer.com/en/track/908604612").kind);
        assertTrue(MusicLinks.parse("https://deezer.page.link/abc").isShort());
        assertEquals(MusicLinks.Kind.SONG, MusicLinks.parse("https://soundcloud.com/theweeknd/blinding-lights").kind);
        assertEquals(MusicLinks.Kind.ARTIST, MusicLinks.parse("https://soundcloud.com/theweeknd").kind);
        assertEquals(MusicLinks.Kind.ARTIST, MusicLinks.parse("https://soundcloud.com/theweeknd/tracks").kind);
        assertNull(MusicLinks.parse("https://soundcloud.com/discover"));

        l = MusicLinks.parse("https://music.amazon.com/albums/B0834VTDJ8?trackAsin=B0834T7TB2");
        assertEquals(MusicLinks.App.AMAZON_MUSIC, l.app); assertEquals(MusicLinks.Kind.SONG, l.kind); assertEquals("B0834T7TB2", l.id);
        assertEquals(MusicLinks.Kind.ALBUM, MusicLinks.parse("https://music.amazon.co.uk/albums/B0834VTDJ8").kind);
        assertEquals(MusicLinks.Kind.EPISODE, MusicLinks.parse("https://music.amazon.com/podcasts/abc/episodes/def").kind);

        assertEquals(MusicLinks.Kind.SHOW, MusicLinks.parse("https://pca.st/podcast/the-daily/abc").kind);
        assertNull(MusicLinks.parse("https://example.com/track/1"));
        assertNull(MusicLinks.parse("spotify:track:abc"));
    }

    @Test public void readsAndMakesSongLinkPages() {
        MusicLinks.Link l = MusicLinks.parse("https://song.link/y/oI2PxA4zfgc");
        assertEquals(MusicLinks.App.YOUTUBE_MUSIC, l.app); assertEquals(MusicLinks.Kind.SONG, l.kind); assertEquals("oI2PxA4zfgc", l.id);
        assertEquals(MusicLinks.App.SPOTIFY, MusicLinks.parse("https://song.link/s/0VjIjW4GlUZAMYd2vXMi3b").app);
        assertEquals("1785425958", MusicLinks.parse("https://song.link/i/1785425958").id);
        assertNull("a custom song.link page isn't one service's song", MusicLinks.parse("https://song.link/some-artist-song"));
        assertEquals("https://song.link/y/oI2PxA4zfgc", MusicLinks.songLink(MusicLinks.parse("https://music.youtube.com/watch?v=oI2PxA4zfgc&si=x"), null));
        assertEquals("https://song.link/s/0VjIjW4GlUZAMYd2vXMi3b", MusicLinks.songLink(MusicLinks.parse("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"), null));
        assertEquals("from the Apple Music match when song.link can't read the link's service", "https://song.link/i/1785425958",
            MusicLinks.songLink(MusicLinks.parse("https://tidal.com/browse/track/1"), "https://music.apple.com/us/album/i-cant-explain/1785425955?i=1785425958"));
        assertNull("albums have no song page", MusicLinks.songLink(MusicLinks.parse("https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj"), null));
    }

    @Test public void matchesNamesTheWayStoresWriteThem() {
        assertTrue(MusicLinks.sameName("Blinding Lights", "blinding lights"));
        assertTrue(MusicLinks.sameName("Mr. Brightside", "Mr Brightside"));
        assertTrue(MusicLinks.sameName("Halo", "Halo (Remastered)"));
        assertTrue(MusicLinks.sameName("Here Comes the Sun - Remastered 2009", "Here Comes The Sun"));
        assertTrue(MusicLinks.sameName("Señorita", "Senorita"));
        assertTrue(MusicLinks.sameName("Save Your Tears (feat. Ariana Grande)", "Save Your Tears"));
        assertFalse(MusicLinks.sameName("Blinding Lights", "Blinding"));
        assertFalse("a live version isn't the original", MusicLinks.sameName("Mr. Brightside (Live At Connect / 2004)", "Mr. Brightside"));
        assertFalse("nor a remix", MusicLinks.sameName("Blinding Lights", "Blinding Lights (Remix)"));
        assertTrue(MusicLinks.sameName("Blinding Lights - Remix", "Blinding Lights (Remixed)"));
        assertTrue("a remaster is the same song", MusicLinks.sameName("Come Together - Remastered 2009", "Come Together"));
        assertTrue(MusicLinks.sameArtist("The Weeknd & Ariana Grande", "The Weeknd"));
        assertTrue(MusicLinks.sameArtist("Simon & Garfunkel", "Simon & Garfunkel"));
        assertTrue(MusicLinks.sameArtist("Beyoncé", "Beyonce"));
        assertFalse(MusicLinks.sameArtist("The Weekend Band", "Weeknd"));
        assertTrue("an unknown artist doesn't rule anything out", MusicLinks.sameArtist("Anyone", ""));
        assertEquals("After Hours", MusicLinks.albumName("After Hours - Single"));
    }

    @Test public void readsSpotifysPage() {
        String html = "<html><head><title>Blinding Lights - song and lyrics by The Weeknd | Spotify</title>"
            + "<meta property=\"og:description\" content=\"The Weeknd · After Hours · Song · 2020\"><meta name=\"music:duration\" content=\"200\"></head></html>";
        MusicLinks.Item i = MusicLinks.spotifyPage(MusicLinks.Kind.SONG, html);
        assertEquals("Blinding Lights", i.title); assertEquals("The Weeknd", i.artist); assertEquals("After Hours", i.album); assertEquals(200_000, i.ms);
        i = MusicLinks.spotifyPage(MusicLinks.Kind.ALBUM, "<title>After Hours - Album by The Weeknd | Spotify</title>");
        assertEquals("After Hours", i.title); assertEquals("The Weeknd", i.artist);
        i = MusicLinks.spotifyPage(MusicLinks.Kind.EPISODE, "<title>The Big Story | Podcast on Spotify</title><meta property=\"og:description\" content=\"Listen to this episode from The Daily on Spotify. Today…\">");
        assertEquals("The Big Story", i.title); assertEquals("The Daily", i.show);
        // Only the short tags: the song is the title and the artist is in the description.
        i = MusicLinks.spotifyPage(MusicLinks.Kind.SONG, "<meta property=\"og:title\" content=\"Blinding Lights\"><meta property=\"og:description\" content=\"Listen to Blinding Lights on Spotify. Song · The Weeknd · 2019\">");
        assertEquals("Blinding Lights", i.title); assertEquals("The Weeknd", i.artist);
    }

    @Test public void readsPodcastEpisodesEverywhere() throws Exception {
        // Spotify's episode pages now say "The Daily · Episode".
        MusicLinks.Item i = MusicLinks.spotifyPage(MusicLinks.Kind.EPISODE, "<title>Grand Theft Auto | Listen to the Podcast on Spotify</title><meta property=\"og:title\" content=\"Grand Theft Auto\"><meta property=\"og:description\" content=\"The Daily · Episode\">");
        assertEquals("Grand Theft Auto", i.title); assertEquals("The Daily", i.show);
        // A YouTube Music podcast (a playlist or its browse page), Overcast's show links (an Apple Podcasts id).
        assertEquals(MusicLinks.Kind.SHOW, MusicLinks.parse("https://music.youtube.com/playlist?list=PLdMrbgYfVl-s16D_iT2BJCJ90pWtTO1A4").kind);
        MusicLinks.Link browse = MusicLinks.parse("https://music.youtube.com/browse/MPSPPLdMrbgYfVl-s16D_iT2BJCJ90pWtTO1A4");
        assertEquals(MusicLinks.Kind.SHOW, browse.kind); assertEquals("PLdMrbgYfVl-s16D_iT2BJCJ90pWtTO1A4", browse.id);
        MusicLinks.Link overcast = MusicLinks.parse("https://overcast.fm/itunes1200361736/the-daily");
        assertEquals(MusicLinks.App.APPLE_PODCASTS, overcast.app); assertEquals("1200361736", overcast.id);
        // What a YouTube Music page opens on, not the videos suggested around it.
        String page = "x\"INITIAL_ENDPOINT\":\"{\\\"watchEndpoint\\\":{\\\"videoId\\\":\\\"abc\\\",\\\"musicVideoType\\\":\\\"MUSIC_VIDEO_TYPE_PODCAST_EPISODE\\\"}}\",\"HOTKEY_DIALOG\":{} MUSIC_VIDEO_TYPE_ATV";
        assertTrue(MusicLinks.initialEndpoint(page).contains("PODCAST_EPISODE"));
        assertFalse(MusicLinks.initialEndpoint(page).contains("ATV"));
    }

    @Test public void matchesEpisodesAcrossApps() {
        assertTrue(MusicLinks.sameEpisode("Olivia Wilde Hit Bottom. Here's How She Bounced Back.", "Olivia Wilde Hit Bottom. Here's How She Bounced Back With ‘The Invite’ and More."));
        assertTrue(MusicLinks.sameEpisode("Grand Theft Auto", "Grand Theft Auto")); // short, but the same
        assertTrue(MusicLinks.sameEpisode("The Ultraviolent Phenomenon of Grand Theft Auto", "The Ultraviolent Phenomenon of Grand Theft Auto: 13 years later"));
        assertFalse(MusicLinks.sameEpisode("The Sunday Read", "The Sunday Read: a story about bees")); // three words alone are too few to be sure
        assertFalse(MusicLinks.sameEpisode("Part 1", "Part 2"));
        assertTrue(MusicLinks.sameShow("The Interview", "The Interview | A Podcast From The New York Times"));
        assertFalse(MusicLinks.sameShow("The Interview", "Interview Kickback"));
        // The same daily title on different days isn't the same episode; the one on the day of the right length is.
        MusicLinks.Item want = new MusicLinks.Item(MusicLinks.Kind.EPISODE); want.title = "Monday Briefing for Investors"; want.date = "2026-11-16"; want.ms = 600_000;
        List<MusicLinks.Item> eps = new ArrayList<>();
        for (String d : new String[]{"2026-11-09", "2026-11-16"}) { MusicLinks.Item e = new MusicLinks.Item(MusicLinks.Kind.EPISODE); e.title = "Monday Briefing for Investors"; e.date = d; e.ms = 590_000; eps.add(e); }
        assertEquals(1, MusicLinks.pickEpisode(want, eps));
        want.title = "Renamed on this app"; assertEquals(1, MusicLinks.pickEpisode(want, eps));
        want.date = "2026-11-20"; assertEquals(-1, MusicLinks.pickEpisode(want, eps));
    }

    @Test public void findsPodcastsInYouTubeMusicsSearch() throws Exception {
        JSONObject episode = o("musicResponsiveListItemRenderer", o(
            "flexColumns", a(o("musicResponsiveListItemFlexColumnRenderer", o("text", o("runs", a(o("text", "Olivia Wilde Hit Bottom. Here's How She Bounced Back With More."))))),
                o("musicResponsiveListItemFlexColumnRenderer", o("text", o("runs", a(o("text", "Episode"), o("text", " • "), o("text", "23h ago"), o("text", " • "), o("text", "The Interview | A Podcast From The New York Times")))))),
            "overlay", o("watchEndpoint", o("videoId", "oHceU_lpCqo", "watchEndpointMusicSupportedConfigs", o("watchEndpointMusicConfig", o("musicVideoType", "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"))))));
        JSONObject show = o("musicResponsiveListItemRenderer", o(
            "flexColumns", a(o("musicResponsiveListItemFlexColumnRenderer", o("text", o("runs", a(o("text", "The Daily"))))),
                o("musicResponsiveListItemFlexColumnRenderer", o("text", o("runs", a(o("text", "Podcast"), o("text", " • "), o("text", "New York Times Podcasts")))))),
            "navigationEndpoint", o("browseEndpoint", o("browseId", "MPSPPLdMrbgYfVl-s16D_iT2BJCJ90pWtTO1A4"))));
        JSONObject root = o("contents", a(show, episode));
        MusicLinks.Item ep = new MusicLinks.Item(MusicLinks.Kind.EPISODE); ep.title = "Olivia Wilde Hit Bottom. Here's How She Bounced Back."; ep.show = "The Interview";
        assertEquals("https://music.youtube.com/watch?v=oHceU_lpCqo", MusicLinks.youtubePodcastResult(root, ep, ep.show));
        ep.show = "The Daily"; assertNull(MusicLinks.youtubePodcastResult(root, ep, ep.show));
        MusicLinks.Item daily = new MusicLinks.Item(MusicLinks.Kind.SHOW); daily.title = "The Daily";
        assertEquals("https://music.youtube.com/playlist?list=PLdMrbgYfVl-s16D_iT2BJCJ90pWtTO1A4", MusicLinks.youtubePodcastResult(root, daily, "The Daily"));
    }

    @Test public void readsOtherPagesTitles() {
        MusicLinks.Item i = MusicLinks.fromTitle(MusicLinks.Kind.SONG, "Blinding Lights by The Weeknd on TIDAL", "", "");
        assertEquals("Blinding Lights", i.title); assertEquals("The Weeknd", i.artist);
        i = MusicLinks.fromTitle(MusicLinks.Kind.SONG, "Play Blinding Lights by The Weeknd on Amazon Music", "", "");
        assertEquals("Blinding Lights", i.title); assertEquals("The Weeknd", i.artist);
        i = MusicLinks.fromTitle(MusicLinks.Kind.SONG, "Blinding Lights", "", "The Weeknd");
        assertEquals("The Weeknd", i.artist);
    }

    @Test public void findsTheSongOnAppleMusic() throws Exception {
        Fake net = new Fake().on(MusicLinks.ITUNES + "search?media=music", o("results", a(
            o("wrapperType", "track", "kind", "song", "trackName", "Blinding Lights", "artistName", "Cover Band", "collectionName", "Covers", "trackTimeMillis", 200000, "trackViewUrl", "https://music.apple.com/us/album/x/1?i=9"),
            o("wrapperType", "track", "kind", "song", "trackName", "Blinding Lights", "artistName", "The Weeknd", "collectionName", "After Hours", "trackTimeMillis", 200040,
                "trackViewUrl", "https://music.apple.com/us/album/blinding-lights/1499378108?i=1499378615&uo=4"))));
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "Blinding Lights"; song.artist = "The Weeknd"; song.ms = 200000;
        MusicLinks.Match m = MusicLinks.find(song, MusicLinks.App.APPLE_MUSIC, net, "US");
        assertTrue(m.exact());
        assertEquals("the cover band's version isn't it, and Apple's tracking tag is dropped", "https://music.apple.com/us/album/blinding-lights/1499378108?i=1499378615", m.url);
        assertTrue(net.asked.get(0).contains("country=us") && net.asked.get(0).contains("term=Blinding%20Lights%20The%20Weeknd"));
    }

    @Test public void aLongRemixIsNotTheSong() throws Exception {
        Fake net = new Fake().on(MusicLinks.DEEZER + "search/track", o("data", a(
            o("id", 1, "title", "Mr. Brightside (Jacques Lu Cont's Thin White Duke Short Version)", "duration", 374, "artist", o("name", "The Killers"), "link", "https://www.deezer.com/track/1"))));
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "Mr. Brightside"; song.artist = "The Killers"; song.ms = 223_000;
        assertEquals("search", MusicLinks.find(song, MusicLinks.App.DEEZER, net, "us").how);
    }

    @Test public void findsTheSongOnDeezerByItsCode() throws Exception {
        Fake net = new Fake().on(MusicLinks.DEEZER + "track/isrc:USUG11904206", o("id", 908604612, "link", "https://www.deezer.com/track/908604612"));
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "Blinding Lights"; song.artist = "The Weeknd"; song.isrc = "USUG11904206";
        assertEquals("https://www.deezer.com/track/908604612", MusicLinks.find(song, MusicLinks.App.DEEZER, net, "us").url);
    }

    @Test public void findsTheSongOnSpotifyThroughMusicBrainz() throws Exception {
        Fake net = new Fake()
            .on(MusicLinks.DEEZER + "search/track", o("data", a(o("id", 908604612, "title", "Blinding Lights", "duration", 200, "artist", o("name", "The Weeknd")))))
            .on(MusicLinks.DEEZER + "track/908604612", o("id", 908604612, "isrc", "USUG11904206"))
            .on(MusicLinks.MB + "isrc/USUG11904206", o("recordings", a(o("id", "rec-1", "title", "Blinding Lights"))))
            .on(MusicLinks.MB + "recording/rec-1", o("id", "rec-1", "relations", a(
                o("url", o("resource", "https://www.deezer.com/track/908604612")),
                o("url", o("resource", "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=1")))));
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "Blinding Lights"; song.artist = "The Weeknd";
        MusicLinks.Match m = MusicLinks.find(song, MusicLinks.App.SPOTIFY, net, "us");
        assertTrue(m.exact()); assertEquals("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b", m.url);
        assertEquals("USUG11904206", song.isrc);
    }

    @Test public void writesDuetCreditsBothWays() {
        assertEquals(java.util.Arrays.asList("Kendrick Lamar & SZA", "Kendrick Lamar, SZA", "Kendrick Lamar"), MusicLinks.credits("Kendrick Lamar & SZA"));
        assertEquals(java.util.Arrays.asList("Shaboozey"), MusicLinks.credits("Shaboozey"));
        assertEquals(java.util.Arrays.asList("Calvin Harris feat. Rihanna", "Calvin Harris, Rihanna", "Calvin Harris"), MusicLinks.credits("Calvin Harris feat. Rihanna"));
    }

    @Test public void asksListenBrainzBeforeAnythingSlower() throws Exception {
        Fake net = new Fake().on(MusicLinks.LB + "spotify-id-from-metadata/json?artist_name=Kendrick%20Lamar%2C%20SZA&release_name=&", "[{\"spotify_track_ids\":[\"2CGNAOSuO1MEFCbBRgUzjd\"]}]");
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "luther"; song.artist = "Kendrick Lamar & SZA";
        assertEquals("https://open.spotify.com/track/2CGNAOSuO1MEFCbBRgUzjd", MusicLinks.find(song, MusicLinks.App.SPOTIFY, net, "us").url);
        assertTrue("found before Deezer or MusicBrainz were asked", net.asked.stream().allMatch(u -> u.startsWith(MusicLinks.LB)));
    }

    @Test public void stopsAskingMusicBrainzWhenTimeRunsOut() throws Exception {
        Fake net = new Fake().on(MusicLinks.MB, o("recordings", a()));
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "Rare"; song.artist = "Nobody"; song.isrc = "XX0000000000"; song.codesAsked = true;
        MusicLinks.deadline.set(System.currentTimeMillis() - 1); MusicLinks.ranOut.set(false);
        try {
            MusicLinks.Match m = MusicLinks.find(song, MusicLinks.App.TIDAL, net, "us");
            assertEquals("search", m.how); assertTrue("it's known to be unfinished", MusicLinks.ranOut.get());
            assertTrue(net.asked.stream().noneMatch(u -> u.startsWith(MusicLinks.MB)));
        } finally { MusicLinks.deadline.set(0L); MusicLinks.ranOut.set(false); }
    }

    @Test public void findsSpotifySongsThroughListenBrainzFirst() throws Exception {
        Fake net = new Fake()
            .on(MusicLinks.DEEZER + "search/track", o("data", a(o("id", 1, "title", "A Bar Song (Tipsy)", "duration", 171, "artist", o("name", "Shaboozey"), "album", o("title", "Where I've Been, Isn't Where I'm Going")))))
            .on(MusicLinks.DEEZER + "track/1", o("id", 1, "isrc", "USUYG1543939"))
            .on(MusicLinks.LB + "spotify-id-from-metadata/json?artist_name=Shaboozey&release_name=Where", "[{\"artist_name\":\"Shaboozey\",\"spotify_track_ids\":[\"5fZJQrFKWQLb7FpJXZ1g7K\",\"6ZGkfAtpzimwD4597JhIsl\"]}]");
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "A Bar Song (Tipsy)"; song.artist = "Shaboozey";
        MusicLinks.Match m = MusicLinks.find(song, MusicLinks.App.SPOTIFY, net, "us");
        assertTrue(m.exact()); assertEquals("https://open.spotify.com/track/5fZJQrFKWQLb7FpJXZ1g7K", m.url);
        assertTrue("the album came from Deezer, since a YouTube Music link doesn't say it", net.asked.stream().anyMatch(u -> u.contains("release_name=Where%20I%E2%80%99ve") || u.contains("release_name=Where%20I%27ve")));
        assertTrue("MusicBrainz wasn't needed", net.asked.stream().noneMatch(u -> u.startsWith(MusicLinks.MB)));
    }

    @Test public void asksListenBrainzWithoutTheAlbumWhenTheAlbumNameDiffers() throws Exception {
        Fake net = new Fake()
            .on(MusicLinks.LB + "spotify-id-from-metadata/json?artist_name=Lady%20Gaga%20%26%20Bruno%20Mars&release_name=Wrong", "[{\"spotify_track_ids\":[]}]")
            .on(MusicLinks.LB + "spotify-id-from-metadata/json?artist_name=Lady%20Gaga%20%26%20Bruno%20Mars&release_name=&", "[{\"spotify_track_ids\":[]}]")
            .on(MusicLinks.LB + "spotify-id-from-metadata/json?artist_name=Lady%20Gaga&release_name=&", "[{\"spotify_track_ids\":[\"2plbrEY59IikOBgBGLjaoe\"]}]");
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "Die With A Smile"; song.artist = "Lady Gaga & Bruno Mars"; song.album = "Wrong"; song.isrc = "USUM72409273";
        assertEquals("https://open.spotify.com/track/2plbrEY59IikOBgBGLjaoe", MusicLinks.find(song, MusicLinks.App.SPOTIFY, net, "us").url);
    }

    @Test public void asksListenBrainzAboutTheRecordingMusicBrainzFound() throws Exception {
        Fake net = new Fake()
            .on(MusicLinks.LB + "spotify-id-from-metadata", "[{\"spotify_track_ids\":[]}]")
            .on(MusicLinks.MB + "isrc/USUG11904206", o("recordings", a(o("id", "rec-1", "title", "Blinding Lights"))))
            .on(MusicLinks.MB + "recording/rec-1", o("id", "rec-1", "relations", a()))
            .on(MusicLinks.LB + "spotify-id-from-mbid/json?recording_mbid=rec-1", "[{\"recording_mbid\":\"rec-1\",\"spotify_track_ids\":[\"0VjIjW4GlUZAMYd2vXMi3b\"]}]");
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "Blinding Lights"; song.artist = "The Weeknd"; song.isrc = "USUG11904206";
        assertEquals("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b", MusicLinks.find(song, MusicLinks.App.SPOTIFY, net, "us").url);
    }

    @Test public void searchesTheAppWhenNothingIsExact() {
        Fake net = new Fake();
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "Brand New Song (feat. Someone)"; song.artist = "New Artist & Friend";
        MusicLinks.Match m = MusicLinks.find(song, MusicLinks.App.SPOTIFY, net, "us");
        assertEquals("search", m.how); assertEquals("https://open.spotify.com/search/Brand%20New%20Song%20New%20Artist", m.url); assertEquals("spotify:search:Brand%20New%20Song%20New%20Artist", m.alt);
        assertEquals("search", MusicLinks.find(song, MusicLinks.App.TIDAL, net, "us").how);
        MusicLinks.Item list = new MusicLinks.Item(MusicLinks.Kind.PLAYLIST); list.title = "My mix";
        assertNull("a person's playlist has no match", MusicLinks.find(list, MusicLinks.App.SPOTIFY, net, "us"));
    }

    @Test public void findsTheSameSongInYouTubeMusicsSearch() throws Exception {
        JSONObject row = o("flexColumns", a(
            o("musicResponsiveListItemFlexColumnRenderer", o("text", o("runs", a(o("text", "Blinding Lights"))))),
            o("musicResponsiveListItemFlexColumnRenderer", o("text", o("runs", a(o("text", "The Weeknd"), o("text", " • "), o("text", "After Hours"), o("text", " • "), o("text", "3:21")))))),
            "playlistItemData", o("videoId", "4NRXx6U8ABQ"));
        JSONObject cover = new JSONObject(row.toString()); cover.getJSONArray("flexColumns").getJSONObject(1).getJSONObject("musicResponsiveListItemFlexColumnRenderer").getJSONObject("text").getJSONArray("runs").getJSONObject(0).put("text", "Piano Covers");
        cover.getJSONObject("playlistItemData").put("videoId", "cover");
        JSONObject shelf = o("musicShelfRenderer", o("contents", a(o("musicResponsiveListItemRenderer", cover), o("musicResponsiveListItemRenderer", row))));
        JSONObject reply = o("contents", o("tabbedSearchResultsRenderer", o("tabs", a(o("content", o("sectionListRenderer", o("contents", a(shelf))))))));
        Fake net = new Fake().on("https://music.youtube.com/youtubei/v1/search", reply);
        MusicLinks.Item song = new MusicLinks.Item(MusicLinks.Kind.SONG); song.title = "Blinding Lights"; song.artist = "The Weeknd";
        MusicLinks.Match m = MusicLinks.find(song, MusicLinks.App.YOUTUBE_MUSIC, net, "us");
        assertEquals("https://music.youtube.com/watch?v=4NRXx6U8ABQ", m.url);
        assertTrue(new JSONObject(net.posted).getJSONObject("context").getJSONObject("client").getString("clientName").equals("WEB_REMIX"));
    }

    @Test public void opensAPodcastInEachPodcastApp() throws Exception {
        Fake net = new Fake().on(MusicLinks.ITUNES + "lookup?id=1200361736", o("results", a(
            o("wrapperType", "track", "kind", "podcast", "collectionId", 1200361736L, "collectionName", "The Daily", "artistName", "The New York Times",
                "feedUrl", "https://feeds.simplecast.com/54nAGcIl", "collectionViewUrl", "https://podcasts.apple.com/us/podcast/the-daily/id1200361736?uo=4"),
            o("wrapperType", "podcastEpisode", "kind", "podcast-episode", "trackId", 1000600000000L, "trackName", "The Big Story", "collectionName", "The Daily", "collectionId", 1200361736L,
                "trackViewUrl", "https://podcasts.apple.com/us/podcast/the-big-story/id1200361736?i=1000600000000&uo=4"))));
        MusicLinks.Item ep = MusicLinks.identify(MusicLinks.parse("https://podcasts.apple.com/us/podcast/the-daily/id1200361736?i=1000600000000"), net);
        assertEquals(MusicLinks.Kind.EPISODE, ep.kind); assertEquals("The Big Story", ep.title); assertEquals("The Daily", ep.show); assertEquals("https://feeds.simplecast.com/54nAGcIl", ep.feed);
        MusicLinks.Match pc = MusicLinks.find(ep, MusicLinks.App.POCKET_CASTS, net, "us");
        assertEquals("https://pca.st/itunes/1200361736", pc.url); assertEquals("pktc://subscribe/feeds.simplecast.com/54nAGcIl", pc.alt); assertEquals("show", pc.how);
        assertEquals("antennapod-subscribe://feeds.simplecast.com/54nAGcIl", MusicLinks.find(ep, MusicLinks.App.ANTENNAPOD, net, "us").url);
        assertEquals("podcastaddict://feeds.simplecast.com/54nAGcIl", MusicLinks.find(ep, MusicLinks.App.PODCAST_ADDICT, net, "us").url);
        assertEquals("search", MusicLinks.find(ep, MusicLinks.App.SPOTIFY, net, "us").how);
        assertEquals("https://open.spotify.com/search/The%20Big%20Story%20The%20Daily", MusicLinks.find(ep, MusicLinks.App.SPOTIFY, net, "us").url);
    }

    @Test public void findsASpotifyShowOnApplePodcasts() throws Exception {
        Fake net = new Fake().on(MusicLinks.ITUNES + "search?media=podcast", o("results", a(
            o("wrapperType", "track", "kind", "podcast", "collectionId", 1200361736L, "collectionName", "The Daily", "feedUrl", "https://feeds.simplecast.com/54nAGcIl",
                "collectionViewUrl", "https://podcasts.apple.com/us/podcast/the-daily/id1200361736?uo=4"))));
        MusicLinks.Item show = new MusicLinks.Item(MusicLinks.Kind.SHOW); show.title = "The Daily";
        MusicLinks.Match m = MusicLinks.find(show, MusicLinks.App.APPLE_PODCASTS, net, "us");
        assertTrue(m.exact()); assertEquals("https://podcasts.apple.com/us/podcast/the-daily/id1200361736", m.url);
    }

    @Test public void followsShortLinks() throws Exception {
        Fake net = new Fake().on("follow:https://spotify.link/abc", "https://spotify.link/abc")
            .on("https://spotify.link/abc", "<html><script>window.top.location = validate(\"https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=x\")</script></html>");
        MusicLinks.Link l = MusicLinks.expand(MusicLinks.parse("https://spotify.link/abc"), net);
        assertEquals(MusicLinks.Kind.SONG, l.kind); assertEquals("0VjIjW4GlUZAMYd2vXMi3b", l.id);
        net = new Fake().on("follow:https://on.soundcloud.com/x", "https://soundcloud.com/theweeknd/blinding-lights");
        assertEquals(MusicLinks.Kind.SONG, MusicLinks.expand(MusicLinks.parse("https://on.soundcloud.com/x"), net).kind);
    }
}
