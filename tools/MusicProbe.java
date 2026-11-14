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
        };
        for (String url : links) {
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
