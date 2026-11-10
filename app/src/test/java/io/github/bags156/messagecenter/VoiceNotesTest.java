package io.github.bags156.messagecenter;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class VoiceNotesTest {
    @Test public void clockReadsLikeAPlayer() {
        assertEquals("0:00", VoiceNotes.clock(0));
        assertEquals("0:07", VoiceNotes.clock(7_000));
        assertEquals("1:42", VoiceNotes.clock(102_000));
        assertEquals("12:05", VoiceNotes.clock(725_000));
        assertEquals("0:01", VoiceNotes.clock(600));
    }
    @Test public void barsKeepEachStretchsLoudestMomentAndFillTheHeight() {
        List<Float> levels = new ArrayList<>();
        for (int i = 0; i < 600; i++) levels.add(i < 300 ? 0.05f : 0.2f);
        levels.set(450, 0.4f);
        float[] bars = VoiceNotes.bars(levels);
        assertEquals(VoiceNotes.BARS, bars.length);
        float max = 0; for (float b : bars) max = Math.max(max, b);
        assertEquals(1f, max, 0.0001);
        assertTrue("quiet half still visible", bars[0] > 0.1f && bars[0] < bars[VoiceNotes.BARS - 1]);
    }
    @Test public void shortAndSilentRecordings() {
        List<Float> few = new ArrayList<>(); few.add(0.3f); few.add(0.1f);
        assertEquals(VoiceNotes.BARS, VoiceNotes.bars(few).length);
        List<Float> silent = new ArrayList<>(); for (int i = 0; i < 100; i++) silent.add(0f);
        for (float b : VoiceNotes.bars(silent)) assertEquals(0f, b, 0);
    }
    @Test public void savedFormRoundTrips() {
        float[] peaks = new float[VoiceNotes.BARS];
        for (int i = 0; i < peaks.length; i++) peaks[i] = i / (float) peaks.length;
        VoiceNotes.Info back = VoiceNotes.parse(VoiceNotes.format(new VoiceNotes.Info(peaks, 45_300)));
        assertEquals(45_300, back.ms);
        assertEquals(peaks.length, back.peaks.length);
        for (int i = 0; i < peaks.length; i++) assertEquals(peaks[i], back.peaks[i], 0.006);
        assertNull(VoiceNotes.parse("garbage"));
    }
}
