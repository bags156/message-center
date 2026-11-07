package io.github.bags156.messagecenter;

import java.util.Arrays;

/** Audio sample conversion for speech recognition. Pure Java so it can be unit tested. */
final class Pcm {
    private Pcm() {}

    /** Android's on-device recognizer is fed 16 kHz, mono, 16-bit audio. */
    static final int RATE = 16000;

    /**
     * Mixes interleaved 16-bit audio to mono and resamples it to 16 kHz with linear
     * interpolation. Keeps its position between calls, so a decoder can feed it buffer by buffer.
     */
    static final class Resampler {
        private final double step;
        private double next = 0;
        private long index = 0;
        private float previous = 0;

        Resampler(int sourceRate) { step = sourceRate / (double) RATE; }

        short[] feed(short[] interleaved, int channels) {
            int ch = Math.max(1, channels), frames = interleaved.length / ch;
            short[] out = new short[(int) (frames / step) + 2];
            int n = 0;
            for (int f = 0; f < frames; f++) {
                int sum = 0;
                for (int c = 0; c < ch; c++) sum += interleaved[f * ch + c];
                float current = sum / (float) ch;
                // Every output time t with index-1 < t <= index falls between the previous and current input sample.
                while (next <= index) {
                    float v = index == 0 ? current : previous + (current - previous) * (float) (next - (index - 1));
                    if (n == out.length) out = Arrays.copyOf(out, out.length * 2);
                    out[n++] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
                    next += step;
                }
                previous = current;
                index++;
            }
            return Arrays.copyOf(out, n);
        }
    }

    /** Little-endian bytes, the layout the recognizer reads from a file. */
    static byte[] bytes(short[] samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) { b[2 * i] = (byte) samples[i]; b[2 * i + 1] = (byte) (samples[i] >> 8); }
        return b;
    }
}
