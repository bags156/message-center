package io.github.bags156.messagecenter;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcmTest {
    @Test public void sixteenKilohertzMonoPassesThrough() {
        short[] in = {0, 100, -100, 32767, -32768};
        assertArrayEquals(in, new Pcm.Resampler(16000).feed(in, 1));
    }

    @Test public void stereoIsAveragedToMono() {
        assertArrayEquals(new short[]{150, -50}, new Pcm.Resampler(16000).feed(new short[]{100, 200, -100, 0}, 2));
    }

    @Test public void downsamplingKeepsDurationAcrossBuffers() {
        Pcm.Resampler r = new Pcm.Resampler(48000);
        int total = 0;
        for (int i = 0; i < 10; i++) total += r.feed(new short[4800], 1).length; // 1 second in 10 buffers
        assertTrue("got " + total, Math.abs(total - 16000) <= 1);
    }

    @Test public void upsamplingInterpolates() {
        short[] out = new Pcm.Resampler(8000).feed(new short[]{0, 1000}, 1);
        assertArrayEquals(new short[]{0, 500, 1000}, out);
    }

    @Test public void bytesAreLittleEndian() {
        assertArrayEquals(new byte[]{0x34, 0x12, (byte) 0xFF, (byte) 0xFF}, Pcm.bytes(new short[]{0x1234, -1}));
    }
}
