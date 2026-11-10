package io.github.bags156.messagecenter;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Audio sample conversion for speech recognition. Pure Java so it can be unit tested. */
final class Pcm {
    private Pcm() {}

    /** Android's on-device recognizer is fed 16 kHz, mono, 16-bit audio. */
    static final int RATE = 16000;

    /**
     * Mixes interleaved 16-bit audio to mono and resamples it to 16 kHz. Other rates go through a
     * windowed-sinc filter: plain interpolation folds everything above 8 kHz (hiss, the "s" in
     * speech) back down into the range the recognizer listens to, where it sounds like noise.
     * The filter is worked out ahead of time for each position between input samples.
     * Keeps its place between calls, so a decoder can feed it buffer by buffer; the filter looks
     * slightly ahead, so call flush() after the last buffer for the final few milliseconds.
     */
    static final class Resampler {
        private static final int ZEROS = 16, MAX_PHASES = 2048;
        private final boolean same;
        private final long up, down;      // output time n is input time n * down / up
        private final int half, taps, phases;
        private final float[][] weights;  // per fractional position: the filter, summing to 1
        private float[] in = new float[8192];
        private int count;
        private long base, total, n;

        Resampler(int sourceRate) {
            same = sourceRate == RATE;
            long g = gcd(sourceRate, RATE);
            up = RATE / g; down = sourceRate / g;
            // Pass band edge as a fraction of the input's Nyquist frequency: just under 8 kHz going down.
            double cutoff = Math.min(1.0, RATE / (double) sourceRate) * 0.92;
            half = same ? 0 : (int) Math.ceil(ZEROS / cutoff);
            taps = 2 * half;
            phases = same ? 0 : (int) Math.min(up, MAX_PHASES);
            weights = new float[phases][taps];
            for (int p = 0; p < phases; p++) {
                double frac = p / (double) phases, sum = 0;
                for (int j = 0; j < taps; j++) {
                    double x = j - half + 1 - frac, u = Math.min(1.0, Math.abs(x) / half), arg = Math.PI * cutoff * x;
                    double w = (x == 0 ? 1 : Math.sin(arg) / arg) * (0.42 + 0.5 * Math.cos(Math.PI * u) + 0.08 * Math.cos(2 * Math.PI * u));
                    weights[p][j] = (float) w; sum += w;
                }
                for (int j = 0; j < taps; j++) weights[p][j] /= (float) sum;
            }
        }

        private static long gcd(long a, long b) { while (b != 0) { long t = a % b; a = b; b = t; } return a; }

        short[] feed(short[] interleaved, int channels) {
            int ch = Math.max(1, channels), frames = interleaved.length / ch;
            if (same) {
                short[] out = new short[frames];
                for (int f = 0; f < frames; f++) out[f] = (short) Math.round(mix(interleaved, f, ch));
                return out;
            }
            room(frames);
            for (int f = 0; f < frames; f++) in[count++] = mix(interleaved, f, ch);
            total += frames;
            return produce(false);
        }

        /** The rest of the audio, once the last buffer has been fed. */
        short[] flush() {
            if (same) return new short[0];
            room(half + 1);
            for (int i = 0; i <= half; i++) in[count++] = 0;
            return produce(true);
        }

        private static float mix(short[] s, int frame, int ch) {
            int sum = 0;
            for (int c = 0; c < ch; c++) sum += s[frame * ch + c];
            return sum / (float) ch;
        }

        private void room(int more) { if (count + more > in.length) in = Arrays.copyOf(in, Math.max(in.length * 2, count + more)); }

        private short[] produce(boolean end) {
            long have = base + count;
            short[] out = new short[(int) Math.max(4, count * up / down + 4)];
            int made = 0;
            while (true) {
                long at = n * down, whole = at / up, rest = at % up;
                if (end ? whole >= total : whole + half >= have) break;
                int p = (int) (phases == up ? rest : Math.round(rest * (double) phases / up));
                if (p == phases) { p = 0; whole++; }
                float[] w = weights[p];
                long first = whole - half + 1;
                double acc = 0;
                int j0 = (int) Math.max(0, base - first), j1 = (int) Math.min(taps, have - first);
                // Before the first sample is silence.
                for (int j = j0, i = (int) (first + j0 - base); j < j1; j++, i++) acc += w[j] * in[i];
                if (made == out.length) out = Arrays.copyOf(out, out.length * 2);
                out[made++] = (short) Math.max(-32768, Math.min(32767, Math.round(acc)));
                n++;
            }
            // Forget input no later output can reach.
            long keepFrom = n * down / up - half + 1;
            int drop = (int) Math.max(0, Math.min(count, keepFrom - base));
            if (drop > 0) { System.arraycopy(in, drop, in, 0, count - drop); count -= drop; base += drop; }
            return Arrays.copyOf(out, made);
        }
    }

    /** Removes DC offset and the rumble below about 40 Hz (handling noise, wind), keeping its state between buffers. */
    static final class HighPass {
        private static final float R = 0.985f;
        private float lastIn, lastOut;
        short[] apply(short[] s) {
            short[] out = new short[s.length];
            for (int i = 0; i < s.length; i++) {
                float y = s[i] - lastIn + R * lastOut;
                lastIn = s[i]; lastOut = y;
                out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(y)));
            }
            return out;
        }
    }

    /**
     * How much to turn a recording up: its loud parts (ignoring the loudest 0.05%, clicks and
     * pops) are brought to about half of full scale, at most 10× louder. Never turned down.
     * levels[a] counts samples whose absolute value is a.
     */
    static double gainFor(long[] levels, long samples) {
        if (samples == 0) return 1;
        long skip = samples / 2000, seen = 0;
        int level = 0;
        for (int a = levels.length - 1; a >= 0; a--) { seen += levels[a]; if (seen > skip) { level = a; break; } }
        if (level < 64) return 1; // silence; turning it up only adds noise
        return Math.max(1, Math.min(10, 16384.0 / level));
    }

    /** Turns up a quiet recording in place (see gainFor). The file is 16-bit little-endian PCM. */
    static void normalize(File pcm) throws IOException {
        long[] levels = new long[32769];
        long samples = 0;
        byte[] buf = new byte[1 << 16];
        try (RandomAccessFile f = new RandomAccessFile(pcm, "rw")) {
            int n;
            while ((n = f.read(buf)) > 0) {
                for (int i = 0; i + 1 < n; i += 2) { levels[Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8)))]++; samples++; }
            }
            double gain = gainFor(levels, samples);
            if (gain < 1.05) return;
            long at = 0;
            f.seek(0);
            while ((n = f.read(buf)) > 0) {
                for (int i = 0; i + 1 < n; i += 2) {
                    int v = (int) Math.round((short) ((buf[i] & 0xff) | (buf[i + 1] << 8)) * gain);
                    v = Math.max(-32768, Math.min(32767, v));
                    buf[i] = (byte) v; buf[i + 1] = (byte) (v >> 8);
                }
                f.seek(at); f.write(buf, 0, n); at += n;
            }
        }
    }

    // ---- Cutting a recording at pauses ----
    /** Level frames are 20 ms. */
    static final int FRAME = RATE / 50;
    private static final int LEAD = 10, TAIL = 10;          // frames of audio kept before and after speech (200 ms)
    private static final int PAUSE = 15, LONG_PAUSE = 30;  // 300 ms, 600 ms
    private static final int SETTLED = 100, LONGEST = 750; // 2 s, 15 s
    private static final int LOOK_BACK = 200;              // 4 s

    /**
     * The stretches of speech in a recording, as [first frame, end frame) from 20 ms levels in
     * dB. Phones' speech recognizers often stop at the first long pause, so each stretch is cut
     * at the pauses: always at a long one (600 ms), and at a shorter one (300 ms) once a stretch
     * is 2 seconds long. A stretch with no pause is cut at its quietest moment after 15 seconds.
     * Silence (relative to the recording's own quiet and loud parts) isn't included.
     */
    static List<int[]> speech(float[] db) {
        List<int[]> out = new ArrayList<>();
        int n = db.length;
        if (n == 0) return out;
        float[] sorted = db.clone();
        Arrays.sort(sorted);
        float low = sorted[n / 10], high = sorted[Math.min(n - 1, n * 95 / 100)];
        float loud = high - low < 6 ? (high < -55 ? Float.MAX_VALUE : -Float.MAX_VALUE) : Math.max(low + 6, low + 0.25f * (high - low));
        int start = -1, lastLoud = -1, quiet = 0;
        for (int i = 0; i < n; i++) {
            if (db[i] >= loud) { if (start < 0) start = Math.max(0, i - LEAD); lastLoud = i; quiet = 0; }
            else quiet++;
            if (start < 0) continue;
            if (quiet >= LONG_PAUSE || quiet >= PAUSE && lastLoud - start >= SETTLED) {
                out.add(new int[]{start, Math.min(n, lastLoud + 1 + TAIL)}); start = -1; continue;
            }
            if (i - start + 1 >= LONGEST) {
                int cut = i;
                for (int k = Math.max(start + 1, i - LOOK_BACK); k <= i; k++) if (db[k] < db[cut]) cut = k;
                out.add(new int[]{start, cut}); start = cut;
            }
        }
        if (start >= 0 && lastLoud >= start) out.add(new int[]{start, Math.min(n, lastLoud + 1 + TAIL)});
        return out;
    }

    /** 20 ms levels of a 16-bit PCM file, in dB below full scale. */
    static float[] levels(File pcm) throws IOException {
        long samples = pcm.length() / 2;
        float[] db = new float[(int) ((samples + FRAME - 1) / FRAME)];
        byte[] buf = new byte[FRAME * 2 * 64];
        double sum = 0; int inFrame = 0, frame = 0;
        try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(pcm))) {
            int got;
            while ((got = in.read(buf)) > 0) {
                for (int i = 0; i + 1 < got; i += 2) {
                    double v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8)) / 32768.0;
                    sum += v * v;
                    if (++inFrame == FRAME) { db[frame++] = (float) (10 * Math.log10(sum / FRAME + 1e-12)); sum = 0; inFrame = 0; }
                }
            }
        }
        if (inFrame > 0 && frame < db.length) db[frame] = (float) (10 * Math.log10(sum / inFrame + 1e-12));
        return db;
    }

    /**
     * Splits a recording into one file per stretch of speech (see speech), each with a little
     * silence before it and more after, so the recognizer hears it end.
     */
    static List<File> split(File pcm, File dir, String name) throws IOException {
        List<File> out = new ArrayList<>();
        List<int[]> parts = speech(levels(pcm));
        byte[] before = new byte[RATE / 5 * 2], after = new byte[RATE * 2];
        try (RandomAccessFile f = new RandomAccessFile(pcm, "r")) {
            for (int[] p : parts) {
                long from = (long) p[0] * FRAME * 2, to = Math.min(f.length(), (long) p[1] * FRAME * 2);
                if (to <= from) continue;
                byte[] data = new byte[(int) (to - from)];
                f.seek(from); f.readFully(data);
                File part = new File(dir, name + "-" + out.size() + ".pcm");
                try (java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(part))) { os.write(before); os.write(data); os.write(after); }
                out.add(part);
            }
        }
        return out;
    }

    /** Little-endian bytes, the layout the recognizer reads from a file. */
    static byte[] bytes(short[] samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) { b[2 * i] = (byte) samples[i]; b[2 * i + 1] = (byte) (samples[i] >> 8); }
        return b;
    }
}
