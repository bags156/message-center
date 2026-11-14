package io.github.bags156.messagecenter;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The song playing on this phone, and a link to it, for ⊕ → Song playing. */
final class NowPlaying {
    private NowPlaying() {}

    static final class Song { String title = "", artist = "", album = "", pkg = "", mediaId = ""; long ms; }

    static ComponentName access(Context c) { return new ComponentName(c, NowPlayingAccess.class); }

    /** Whether Android lets Message Center see what's playing (notification access, turned on in Android's settings). */
    static boolean allowed(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(access(c));
    }

    /** What's playing (or paused, if nothing is playing), or null. */
    static Song current(Context c) {
        List<MediaController> sessions;
        try { sessions = c.getSystemService(MediaSessionManager.class).getActiveSessions(access(c)); } catch (Exception e) { return null; }
        MediaController best = null;
        for (MediaController s : sessions) {
            if (s.getMetadata() == null) continue;
            PlaybackState state = s.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) { best = s; break; }
            if (best == null) best = s;
        }
        if (best == null) return null;
        MediaMetadata md = best.getMetadata();
        Song song = new Song();
        song.title = first(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        song.artist = first(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            .replaceFirst("\\s+-\\s+Topic$", "");
        song.album = first(md, MediaMetadata.METADATA_KEY_ALBUM);
        song.mediaId = first(md, MediaMetadata.METADATA_KEY_MEDIA_ID);
        song.ms = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        song.pkg = best.getPackageName();
        return song.title.isEmpty() ? null : song;
    }

    private static String first(MediaMetadata md, String... keys) {
        for (String k : keys) { CharSequence v = md.getText(k); if (v != null && v.toString().trim().length() > 0) return v.toString().trim(); }
        return "";
    }

    /** The music app this package is, or null. */
    static MusicLinks.App app(String pkg) {
        for (MusicLinks.App a : MusicLinks.App.values()) if (a.music && !a.pkg.isEmpty() && a.pkg.equals(pkg)) return a;
        return null;
    }

    /**
     * A link to the song, from the ID the playing app gives (Spotify's track, YouTube Music's video),
     * else the exact song found by name on the app playing it, your music app, or Apple Music. Null
     * when none has it exactly. Network: call off the main thread.
     */
    static String link(Context c, Song song, MusicLinks.App mine) {
        Matcher m = Pattern.compile("spotify:track:([A-Za-z0-9]{22})").matcher(song.mediaId);
        if (m.find()) return "https://open.spotify.com/track/" + m.group(1);
        MusicLinks.App playing = app(song.pkg);
        if (playing == MusicLinks.App.YOUTUBE_MUSIC && song.mediaId.matches("[A-Za-z0-9_-]{11}")) return "https://music.youtube.com/watch?v=" + song.mediaId;
        MusicLinks.Item item = new MusicLinks.Item(MusicLinks.Kind.SONG);
        item.title = song.title; item.artist = song.artist; item.album = MusicLinks.albumName(song.album); item.ms = song.ms;
        String version;
        try { version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { version = "1"; }
        MusicLinks.Net net = new MusicLinks.Http("MessageCenter/" + version + " ( https://github.com/bags156/message-center )");
        List<MusicLinks.App> tries = new ArrayList<>();
        for (MusicLinks.App a : new MusicLinks.App[]{playing, mine, MusicLinks.App.APPLE_MUSIC}) if (a != null && a.music && !tries.contains(a)) tries.add(a);
        MusicLinks.deadline.set(System.currentTimeMillis() + 6000);
        try {
            for (MusicLinks.App a : tries) {
                MusicLinks.Match found = MusicLinks.find(item, a, net, java.util.Locale.getDefault().getCountry());
                if (found != null && found.exact()) return found.url;
            }
        } finally { MusicLinks.deadline.set(0L); }
        return null;
    }
}
