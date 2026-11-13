package io.github.bags156.messagecenter;

import org.json.JSONObject;

/**
 * Which arriving photos to start fetching right away, so they're ready when you open the chat
 * instead of starting then. Pure Java for unit tests; PairedService does the fetching.
 */
final class PhotoPrefetch {
    private PhotoPrefetch() {}

    /** Photos sent in the last few days: an old one turning up again (a resync) isn't fetched on its own. */
    static final long RECENT_MS = 3L * 24 * 60 * 60 * 1000;
    /** The same limit the chat uses for showing full photos. */
    static final long MAX_BYTES = 25L << 20;

    /**
     * A photo (still or animated) someone sent you recently, not too big, while "Download full-quality
     * photos" is on and Android's Data Saver isn't holding back a metered connection.
     */
    static boolean wanted(JSONObject message, JSONObject media, long now, boolean fullPhotos, boolean dataSaver) {
        if (!fullPhotos || dataSaver || message == null || media == null) return false;
        if (PairedProtocol.outgoing(message)) return false;
        if (!"image".equals(PairedProtocol.mediaKind(media))) return false;
        if (media.optLong("size") > MAX_BYTES) return false;
        long at = message.optLong("timestamp") / 1000;
        return at <= 0 || now - at <= RECENT_MS;
    }
}
