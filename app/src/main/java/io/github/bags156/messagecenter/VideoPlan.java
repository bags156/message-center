package io.github.bags156.messagecenter;

import java.util.Locale;

/**
 * How a video is made ready to send (see VideoPrep, which does it). A phone video is often
 * 4K at 60 frames a second: hundreds of MB a minute, over the 100 MB Google Messages sends,
 * and it crosses the network three times (up to Google, down to your phone, out to them).
 * <ul>
 * <li>RCS: H.264 at up to 1080p and 30 frames a second, about 5 Mbps (a minute is about
 * 40 MB). Longer videos step down (720p, 540p, 360p) to stay well under 100 MB.</li>
 * <li>MMS: a picture message carries about 1 MB, so the whole video has to fit that: small
 * (480p down to 144p), fewer frames, and mono sound. Only about a minute fits at all.</li>
 * </ul>
 * A video already small and in a format every phone plays (H.264 with AAC sound, in MP4) is
 * sent as it is. Plain Java, so it can be tested without a phone.
 * <p>Quality (RCS only; MMS always has to fit 1 MB), see PhotoQuality's names:
 * Fast is up to 720p (about 20 MB a minute), Standard up to 1080p (about 40 MB a minute), and
 * Full sends the original whenever it's under 100 MB, otherwise 1080p at twice the bitrate.
 */
final class VideoPlan {
    /** The most Google Messages sends over RCS, and what's aimed for (variable bitrate can run over). */
    static final long RCS_LIMIT = 100_000_000L, RCS_BUDGET = 80_000_000L;
    /** What a picture message carries (as for photos, see PhotoPrep). */
    static final long MMS_BYTES = 950_000L;
    static final int[] RCS_SIDES = {1080, 720, 540, 360}, MMS_SIDES = {480, 360, 240, 144};
    static final int RCS_LEAST = 250_000, MMS_LEAST = 60_000, MMS_AUDIO = 32_000;

    /** What's known about the video picked. */
    static final class Source {
        int width, height, channels;
        long durationMs, bytes;
        float frameRate; // 0 if unknown
        String container = "", video = "", audio = ""; // MIME types; audio "" if it has no sound
        boolean hdr;
    }

    /** Send the file as it is. */
    final boolean asIs;
    /** Why it can't be sent this way (shown to you), or null. */
    final String problem;
    /** The shorter side to scale to (0 keeps the size), the most frames a second to keep (0 keeps them all). */
    final int shortSide;
    final float frameRate;
    /** Bits a second for the picture, and for the sound (0: copy the sound, or the usual if it must change). */
    final int videoBitrate, audioBitrate;
    final boolean mono;

    private VideoPlan(boolean asIs, String problem, int shortSide, float frameRate, int videoBitrate, int audioBitrate, boolean mono) {
        this.asIs = asIs; this.problem = problem; this.shortSide = shortSide; this.frameRate = frameRate;
        this.videoBitrate = videoBitrate; this.audioBitrate = audioBitrate; this.mono = mono;
    }

    /** RCS picture bitrate for a short side: 5 Mbps at 1080p, in proportion to the pixels below that. */
    static int rcsBitrate(int shortSide) {
        double s = shortSide / 1080.0;
        return (int) Math.max(400_000, Math.round(5_000_000 * s * s));
    }

    static VideoPlan of(Source s, boolean mms) { return of(s, mms, PhotoQuality.STANDARD); }

    /** The largest short side for a quality, and how much more bitrate than Standard it gets. */
    static int topSide(String quality) { return PhotoQuality.FAST.equals(PhotoQuality.normalize(quality)) ? 720 : 1080; }
    static double richness(String quality) { return PhotoQuality.FULL.equals(PhotoQuality.normalize(quality)) ? 2.0 : 1.0; }

    /** What a quality means for videos, for the chooser. */
    static String describe(String quality) {
        switch (PhotoQuality.normalize(quality)) {
            case PhotoQuality.FAST: return "Up to 720p, about 20 MB a minute";
            case PhotoQuality.FULL: return "The original when it's under 100 MB, otherwise sharp 1080p";
            default: return "Up to 1080p, about 40 MB a minute";
        }
    }

    static VideoPlan of(Source s, boolean mms, String quality) {
        int shortIn = Math.max(2, Math.min(s.width, s.height));
        double seconds = Math.max(1, s.durationMs / 1000.0);
        String video = lower(s.video), audio = lower(s.audio), box = lower(s.container);
        boolean avc = video.equals("video/avc"), aac = audio.isEmpty() || audio.equals("audio/mp4a-latm");
        boolean mp4 = box.equals("video/mp4") || box.equals("video/3gpp");
        boolean plain = avc && aac && mp4 && !s.hdr;
        if (mms) {
            if (plain && s.bytes > 0 && s.bytes <= MMS_BYTES) return keep();
            long fits = Math.round(MMS_BYTES * 0.85); // the rest is the file's own overhead
            int audioBps = audio.isEmpty() ? 0 : MMS_AUDIO;
            long videoBps = Math.min(700_000, Math.round(fits * 8 / seconds) - audioBps);
            if (videoBps < MMS_LEAST) {
                long most = fits * 8 / (MMS_LEAST + audioBps);
                return new VideoPlan(false, "A picture message (MMS) fits about " + most + " seconds of video, and this one is "
                        + Math.round(seconds) + ". Send a shorter clip, or send it in an RCS chat.", 0, 0, 0, 0, false);
            }
            int side = videoBps >= 450_000 ? 480 : videoBps >= 250_000 ? 360 : videoBps >= 130_000 ? 240 : 144;
            float fps = videoBps < 250_000 ? 15 : videoBps < 450_000 ? 24 : 30;
            return new VideoPlan(false, null, side < shortIn ? even(side) : 0, s.frameRate > 0 && s.frameRate <= fps + 1 ? 0 : fps,
                    (int) videoBps, audioBps, audioBps > 0);
        }
        // Full: the original, whatever its format, as long as it can be sent at all.
        if (PhotoQuality.FULL.equals(PhotoQuality.normalize(quality)) && s.bytes > 0 && s.bytes <= RCS_LIMIT) return keep();
        int top = topSide(quality);double rich = richness(quality);
        int audioGuess = audio.isEmpty() ? 0 : aac ? 256_000 : 128_000;
        long most = Math.round(RCS_BUDGET * 8 / seconds) - audioGuess;
        // Step down a size only when even Standard's bitrate for it won't fit; Full then gets as
        // much more than Standard as fits, rather than a smaller picture.
        int side = Math.min(top, shortIn);
        for (int i = 0; i < RCS_SIDES.length && rcsBitrate(side) > most; i++)
            if (RCS_SIDES[i] < side) side = RCS_SIDES[i];
        int bps = (int) Math.max(RCS_LEAST, Math.min(Math.round(rcsBitrate(side) * rich), most));
        boolean smaller = side < shortIn, fast = s.frameRate > 31;
        // Already a sensible size and bitrate (a third over what it'd be made into is fine).
        double fair = (rcsBitrate(Math.min(top, shortIn)) * rich + audioGuess) * seconds / 8 * 1.35;
        if (plain && !smaller && !fast && s.bytes > 0 && s.bytes <= RCS_BUDGET && s.bytes <= fair) return keep();
        return new VideoPlan(false, null, smaller ? even(side) : 0, fast ? 30 : 0, bps, 0, false);
    }

    /**
     * The next try when the last came out too big (cameOut bytes): a step smaller, and a lower
     * bitrate, in proportion to how far over it was (encoders don't all keep to what's asked).
     */
    VideoPlan smaller(Source s, boolean mms, long cameOut) {
        int[] sides = mms ? MMS_SIDES : RCS_SIDES;
        int now = shortSide > 0 ? shortSide : Math.min(s.width, s.height), next = now;
        for (int side : sides) if (side < now) { next = side; break; }
        int least = mms ? MMS_LEAST : RCS_LEAST;
        double aim = mms ? MMS_BYTES * 0.85 : RCS_BUDGET, scale = Math.min(0.65, cameOut > 0 ? aim / cameOut * 0.9 : 0.65);
        return new VideoPlan(false, null, next < Math.min(s.width, s.height) ? even(next) : 0, frameRate,
                (int) Math.max(least, Math.round(videoBitrate * scale)), audioBitrate, mono);
    }

    private static VideoPlan keep() { return new VideoPlan(true, null, 0, 0, 0, 0, false); }
    private static int even(int n) { return Math.max(2, n & ~1); }
    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    @Override public String toString() {
        if (asIs) return "as it is";
        if (problem != null) return "can't";
        return (shortSide > 0 ? shortSide + "p" : "same size") + (frameRate > 0 ? " " + Math.round(frameRate) + " fps" : "")
                + " " + videoBitrate / 1000 + " kbps" + (mono ? " mono" : "") + (audioBitrate > 0 ? " sound " + audioBitrate / 1000 + " kbps" : "");
    }
}
