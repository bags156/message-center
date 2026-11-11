package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VideoPlanTest {
    private static VideoPlan.Source video(int w, int h, float fps, long seconds, long bytes, String codec, String audio, boolean hdr) {
        VideoPlan.Source s = new VideoPlan.Source();
        s.width = w; s.height = h; s.frameRate = fps; s.durationMs = seconds * 1000; s.bytes = bytes;
        s.container = "video/mp4"; s.video = codec; s.audio = audio; s.channels = audio.isEmpty() ? 0 : 2; s.hdr = hdr;
        return s;
    }

    @Test public void phoneCamera4kHdrBecomes1080pAt30() {
        VideoPlan p = VideoPlan.of(video(3840, 2160, 60, 60, 420_000_000L, "video/hevc", "audio/mp4a-latm", true), false);
        assertFalse(p.asIs); assertNull(p.problem);
        assertEquals(1080, p.shortSide); assertEquals(30f, p.frameRate, 0.01); assertEquals(5_000_000, p.videoBitrate);
        assertEquals(0, p.audioBitrate); assertFalse(p.mono);
        // About 40 MB a minute: well under what RCS sends.
        assertTrue((p.videoBitrate + 256_000) * 60L / 8 < VideoPlan.RCS_BUDGET);
    }

    @Test public void portraitKeepsItsShortSide() {
        VideoPlan p = VideoPlan.of(video(2160, 3840, 30, 30, 200_000_000L, "video/hevc", "audio/mp4a-latm", false), false);
        assertEquals(1080, p.shortSide); assertEquals(0f, p.frameRate, 0.01);
    }

    @Test public void alreadySensibleIsSentAsItIs() {
        // 1080p H.264 at about 6 Mbps, 20 seconds (15 MB).
        assertTrue(VideoPlan.of(video(1920, 1080, 30, 20, 15_000_000L, "video/avc", "audio/mp4a-latm", false), false).asIs);
        // No sound at all is fine too.
        assertTrue(VideoPlan.of(video(1280, 720, 30, 10, 3_000_000L, "video/avc", "", false), false).asIs);
    }

    @Test public void heavyButRightSizeIsReEncodedAtTheSameSize() {
        VideoPlan p = VideoPlan.of(video(1920, 1080, 30, 20, 40_000_000L, "video/avc", "audio/mp4a-latm", false), false);
        assertFalse(p.asIs); assertEquals(0, p.shortSide); assertEquals(5_000_000, p.videoBitrate);
    }

    @Test public void hdrOrHevcOrOddContainersAreAlwaysConverted() {
        assertFalse(VideoPlan.of(video(1920, 1080, 30, 20, 10_000_000L, "video/avc", "audio/mp4a-latm", true), false).asIs);
        assertFalse(VideoPlan.of(video(1920, 1080, 30, 20, 10_000_000L, "video/hevc", "audio/mp4a-latm", false), false).asIs);
        VideoPlan.Source webm = video(1280, 720, 30, 20, 5_000_000L, "video/x-vnd.on2.vp9", "audio/opus", false); webm.container = "video/webm";
        assertFalse(VideoPlan.of(webm, false).asIs);
        assertFalse(VideoPlan.of(video(1920, 1080, 60, 20, 15_000_000L, "video/avc", "audio/mp4a-latm", false), false).asIs);
    }

    @Test public void longVideosStepDownToStayUnderTheLimit() {
        VideoPlan p = VideoPlan.of(video(1920, 1080, 30, 600, 1_500_000_000L, "video/avc", "audio/mp4a-latm", false), false);
        assertEquals(360, p.shortSide);
        assertTrue((p.videoBitrate + 256_000L) * 600 / 8 <= VideoPlan.RCS_BUDGET);
        VideoPlan q = VideoPlan.of(video(1920, 1080, 30, 150, 400_000_000L, "video/avc", "audio/mp4a-latm", false), false);
        assertEquals(720, q.shortSide);
    }

    @Test public void smallVideosAreNeverMadeBigger() {
        VideoPlan p = VideoPlan.of(video(640, 360, 30, 30, 60_000_000L, "video/hevc", "audio/mp4a-latm", false), false);
        assertEquals(0, p.shortSide); assertEquals(VideoPlan.rcsBitrate(360), p.videoBitrate);
    }

    @Test public void pictureMessageFitsAboutAMegabyte() {
        VideoPlan p = VideoPlan.of(video(1920, 1080, 30, 10, 20_000_000L, "video/avc", "audio/mp4a-latm", false), true);
        assertFalse(p.asIs); assertNull(p.problem);
        assertEquals(480, p.shortSide); assertEquals(0f, p.frameRate, 0.01); assertTrue(p.mono); assertEquals(VideoPlan.MMS_AUDIO, p.audioBitrate);
        assertTrue((p.videoBitrate + p.audioBitrate) * 10L / 8 <= VideoPlan.MMS_BYTES);
        VideoPlan longer = VideoPlan.of(video(1920, 1080, 60, 30, 90_000_000L, "video/avc", "audio/mp4a-latm", false), true);
        assertEquals(240, longer.shortSide); assertEquals(15f, longer.frameRate, 0.01);
        assertTrue((longer.videoBitrate + longer.audioBitrate) * 30L / 8 <= VideoPlan.MMS_BYTES);
    }

    @Test public void tooLongForAPictureMessageSaysSo() {
        VideoPlan p = VideoPlan.of(video(1920, 1080, 30, 120, 200_000_000L, "video/avc", "audio/mp4a-latm", false), true);
        assertNotNull(p.problem);
        assertTrue(p.problem, p.problem.contains("about 70 seconds") && p.problem.contains("this one is 120"));
    }

    @Test public void tinyClipGoesToMmsAsItIs() {
        assertTrue(VideoPlan.of(video(640, 360, 30, 4, 700_000L, "video/avc", "audio/mp4a-latm", false), true).asIs);
        assertFalse(VideoPlan.of(video(640, 360, 30, 4, 1_200_000L, "video/avc", "audio/mp4a-latm", false), true).asIs);
    }

    @Test public void nextTryIsSmaller() {
        VideoPlan.Source s = video(3840, 2160, 30, 60, 400_000_000L, "video/hevc", "audio/mp4a-latm", false);
        VideoPlan p = VideoPlan.of(s, false), q = p.smaller(s, false, 110_000_000L);
        assertEquals(720, q.shortSide); assertEquals(Math.round(p.videoBitrate * 0.65), q.videoBitrate, 1);
        VideoPlan.Source m = video(1920, 1080, 30, 10, 20_000_000L, "video/avc", "audio/mp4a-latm", false);
        VideoPlan a = VideoPlan.of(m, true), b = a.smaller(m, true, 1_000_000L);
        assertEquals(360, b.shortSide); assertTrue(b.mono); assertEquals(Math.round(a.videoBitrate * 0.65), b.videoBitrate, 1);
        // Four times over: the bitrate drops to match, not just by a third.
        VideoPlan c = a.smaller(m, true, 3_800_000L);
        assertTrue(c.videoBitrate < a.videoBitrate / 4); assertTrue(c.videoBitrate >= VideoPlan.MMS_LEAST);
    }

    @Test public void fastKeepsVideosAt720p() {
        VideoPlan p = VideoPlan.of(video(3840, 2160, 30, 60, 400_000_000L, "video/hevc", "audio/mp4a-latm", false), false, PhotoQuality.FAST);
        assertEquals(720, p.shortSide); assertEquals(VideoPlan.rcsBitrate(720), p.videoBitrate);
        // A sensible 1080p clip still comes down to 720p.
        assertEquals(720, VideoPlan.of(video(1920, 1080, 30, 20, 15_000_000L, "video/avc", "audio/mp4a-latm", false), false, PhotoQuality.FAST).shortSide);
        // Picture messages don't change.
        VideoPlan.Source m = video(1920, 1080, 30, 10, 20_000_000L, "video/avc", "audio/mp4a-latm", false);
        assertEquals(VideoPlan.of(m, true).toString(), VideoPlan.of(m, true, PhotoQuality.FAST).toString());
    }

    @Test public void fullSendsTheOriginalWhenItFits() {
        // Even HEVC or HDR: Full means the original.
        assertTrue(VideoPlan.of(video(3840, 2160, 60, 20, 90_000_000L, "video/hevc", "audio/mp4a-latm", true), false, PhotoQuality.FULL).asIs);
        // Too big for RCS: sharp 1080p at twice Standard's bitrate.
        VideoPlan p = VideoPlan.of(video(3840, 2160, 60, 60, 420_000_000L, "video/hevc", "audio/mp4a-latm", true), false, PhotoQuality.FULL);
        assertFalse(p.asIs); assertEquals(1080, p.shortSide); assertEquals(10_000_000, p.videoBitrate);
        assertTrue((p.videoBitrate + 256_000L) * 60 / 8 <= VideoPlan.RCS_BUDGET);
        // Longer: it stays 1080p with as much as fits, never worse than Standard.
        VideoPlan q = VideoPlan.of(video(3840, 2160, 30, 120, 800_000_000L, "video/hevc", "audio/mp4a-latm", false), false, PhotoQuality.FULL);
        VideoPlan std = VideoPlan.of(video(3840, 2160, 30, 120, 800_000_000L, "video/hevc", "audio/mp4a-latm", false), false);
        assertEquals(1080, q.shortSide); assertTrue(q.videoBitrate >= std.videoBitrate);
        assertTrue((q.videoBitrate + 256_000L) * 120 / 8 <= VideoPlan.RCS_BUDGET);
        // Picture messages still squeeze under 1 MB.
        assertFalse(VideoPlan.of(video(1920, 1080, 30, 10, 20_000_000L, "video/avc", "audio/mp4a-latm", false), true, PhotoQuality.FULL).asIs);
    }

    @Test public void standardIsTheDefault() {
        VideoPlan.Source s = video(3840, 2160, 60, 60, 420_000_000L, "video/hevc", "audio/mp4a-latm", true);
        assertEquals(VideoPlan.of(s, false).toString(), VideoPlan.of(s, false, PhotoQuality.STANDARD).toString());
        assertEquals(VideoPlan.of(s, false).toString(), VideoPlan.of(s, false, "").toString());
    }
}
