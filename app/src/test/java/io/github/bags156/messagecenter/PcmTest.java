package io.github.bags156.messagecenter;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcmTest {
    @Test public void sixteenKilohertzMonoPassesThrough() {
        short[] in = {0, 100, -100, 32767, -32768};
        assertArrayEquals(in, new Pcm.Resampler(16000).feed(in, 1));
        assertEquals(0, new Pcm.Resampler(16000).flush().length);
    }

    @Test public void stereoIsAveragedToMono() {
        assertArrayEquals(new short[]{150, -50}, new Pcm.Resampler(16000).feed(new short[]{100, 200, -100, 0}, 2));
    }

    @Test public void downsamplingKeepsDurationAcrossBuffers() {
        Pcm.Resampler r = new Pcm.Resampler(48000);
        int total = 0;
        for (int i = 0; i < 10; i++) total += r.feed(new short[4800], 1).length; // 1 second in 10 buffers
        total += r.flush().length;
        assertTrue("got " + total, Math.abs(total - 16000) <= 1);
    }

    @Test public void upsamplingKeepsDuration() {
        Pcm.Resampler r = new Pcm.Resampler(8000);
        int total = r.feed(new short[8000], 1).length + r.flush().length;
        assertTrue("got " + total, Math.abs(total - 16000) <= 1);
    }

    /** A tone's level in the middle of a resampled second (away from the edges). */
    private static double rms(int sourceRate, double hz) {
        short[] in = new short[sourceRate];
        for (int i = 0; i < in.length; i++) in[i] = (short) Math.round(10000 * Math.sin(2 * Math.PI * hz * i / sourceRate));
        Pcm.Resampler r = new Pcm.Resampler(sourceRate);
        short[] a = r.feed(in, 1), b = r.flush();
        short[] out = new short[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length); System.arraycopy(b, 0, out, a.length, b.length);
        double sum = 0; int n = 0;
        for (int i = out.length / 4; i < out.length * 3 / 4; i++) { sum += out[i] * (double) out[i]; n++; }
        return Math.sqrt(sum / n);
    }

    @Test public void speechFrequenciesKeepTheirLevel() {
        double expected = 10000 / Math.sqrt(2);
        assertEquals(expected, rms(48000, 1000), expected * 0.03);
        assertEquals(expected, rms(44100, 3000), expected * 0.03);
        assertEquals(expected, rms(8000, 440), expected * 0.03);
    }

    @Test public void hissAbove8kHzIsFilteredInsteadOfFoldedIntoSpeech() {
        // Plain interpolation would fold 10 kHz down to 6 kHz at nearly full level.
        assertTrue(rms(48000, 10000) < 70);
        assertTrue(rms(44100, 12000) < 70);
    }

    @Test public void highPassRemovesDcOffset() {
        Pcm.HighPass h = new Pcm.HighPass();
        short[] s = new short[4000];
        java.util.Arrays.fill(s, (short) 3000);
        short[] out = h.apply(s);
        assertTrue("left " + out[out.length - 1], Math.abs(out[out.length - 1]) < 10);
    }

    @Test public void quietRecordingsAreTurnedUpAndLoudOnesLeftAlone() {
        long[] quiet = new long[32769]; quiet[1638] = 100_000;            // peaks around -26 dBFS
        assertEquals(10.0, Pcm.gainFor(quiet, 100_000), 0.01);           // capped at 10x
        long[] soft = new long[32769]; soft[4096] = 100_000;
        assertEquals(4.0, Pcm.gainFor(soft, 100_000), 0.01);
        long[] loud = new long[32769]; loud[30000] = 100_000;
        assertEquals(1.0, Pcm.gainFor(loud, 100_000), 0.001);
        long[] silent = new long[32769]; silent[10] = 100_000;
        assertEquals(1.0, Pcm.gainFor(silent, 100_000), 0.001);
        long[] click = new long[32769]; click[4096] = 100_000; click[32767] = 10; // a few clicks don't count
        assertEquals(4.0, Pcm.gainFor(click, 100_010), 0.01);
    }

    @Test public void normalizeTurnsUpAFile() throws Exception {
        java.io.File f = java.io.File.createTempFile("pcm", ".raw");
        try {
            short[] s = new short[16000];
            for (int i = 0; i < s.length; i++) s[i] = (short) Math.round(2000 * Math.sin(i / 5.0));
            java.nio.file.Files.write(f.toPath(), Pcm.bytes(s));
            Pcm.normalize(f);
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            int peak = 0;
            for (int i = 0; i + 1 < b.length; i += 2) peak = Math.max(peak, Math.abs((short) ((b[i] & 0xff) | (b[i + 1] << 8))));
            assertTrue("peak " + peak, peak > 15000 && peak <= 16500);
        } finally { f.delete(); }
    }

    @Test public void bytesAreLittleEndian() {
        assertArrayEquals(new byte[]{0x34, 0x12, (byte) 0xFF, (byte) 0xFF}, Pcm.bytes(new short[]{0x1234, -1}));
    }
}
