package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What a voice message bubble shows before it's played: its waveform and length, worked out once
 * on the phone from the audio and saved. Also which incoming voice messages you've played.
 */
final class VoiceNotes {
    static final int BARS = 60;

    static final class Info {
        final float[] peaks;
        final long ms;
        Info(float[] peaks, long ms) { this.peaks = peaks; this.ms = ms; }
    }

    interface Ready { void ready(Info info); }

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static Handler main;
    private static final Set<String> working = new HashSet<>();

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("voice_notes", Context.MODE_PRIVATE); }

    /** The saved waveform and length, or null if it hasn't been worked out yet. */
    static Info saved(Context context, String mediaID) {
        String s = mediaID.isEmpty() ? null : prefs(context).getString("w:" + mediaID, null);
        return s == null ? null : parse(s);
    }

    /** Works out the waveform in the background (once per message at a time); ready runs on the main thread. */
    static void analyze(Context context, File audio, String mediaID, Ready ready) {
        if (mediaID.isEmpty() || !working.add(mediaID)) return;
        Context app = context.getApplicationContext();
        if (main == null) main = new Handler(Looper.getMainLooper());
        worker.execute(() -> {
            Info info = null;
            try { info = measure(audio); } catch (Exception ignored) {}
            Info result = info;
            if (result != null) prefs(app).edit().putString("w:" + mediaID, format(result)).apply();
            main.post(() -> { working.remove(mediaID); if (result != null) ready.ready(result); });
        });
    }

    static boolean played(Context context, String mediaID) { return prefs(context).getBoolean("p:" + mediaID, false); }
    static void markPlayed(Context context, String mediaID) { if (!mediaID.isEmpty()) prefs(context).edit().putBoolean("p:" + mediaID, true).apply(); }

    /** Decodes the audio and keeps the loudness of each 20 ms, then reduces that to BARS bars. */
    private static Info measure(File in) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        List<Float> levels = new ArrayList<>();
        long samplesSeen = 0;
        int rate = 0;
        try {
            extractor.setDataSource(in.getPath());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { extractor.selectTrack(i); format = f; break; }
            }
            if (format == null) return null;
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int window = Math.max(1, rate / 50), inWindow = 0;
            double sum = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            long started = System.currentTimeMillis();
            while (System.currentTimeMillis() - started < 20_000) {
                if (!inputDone) {
                    int ii = codec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(ii);
                        int n = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                        else { codec.queueInputBuffer(ii, 0, n, extractor.getSampleTime(), 0); extractor.advance(); }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    window = Math.max(1, rate / 50);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) return null;
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        short[] s = new short[info.size / 2];
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
                        int ch = Math.max(1, channels);
                        for (int f = 0; f + ch <= s.length; f += ch) {
                            double v = 0;
                            for (int c = 0; c < ch; c++) v += s[f + c];
                            v /= ch * 32768.0;
                            sum += v * v;
                            if (++inWindow == window) { levels.add((float) Math.sqrt(sum / window)); sum = 0; inWindow = 0; }
                            samplesSeen++;
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            extractor.release();
        }
        if (rate <= 0 || levels.isEmpty()) return null;
        return new Info(bars(levels), samplesSeen * 1000 / rate);
    }

    /**
     * BARS bars from 20 ms loudness levels: each bar the loudest moment it covers, scaled so the
     * loudest bar is full height and on a curve so quiet speech is still visible.
     */
    static float[] bars(List<Float> levels) {
        float[] out = new float[BARS];
        float max = 0;
        for (int i = 0; i < BARS; i++) {
            int a = i * levels.size() / BARS, b = Math.max(a + 1, (i + 1) * levels.size() / BARS);
            float v = 0;
            for (int k = a; k < b && k < levels.size(); k++) v = Math.max(v, levels.get(k));
            out[i] = v; max = Math.max(max, v);
        }
        if (max <= 0) return out;
        for (int i = 0; i < BARS; i++) out[i] = (float) Math.pow(out[i] / max, 0.7);
        return out;
    }

    static String format(Info info) {
        StringBuilder b = new StringBuilder().append(info.ms);
        for (float p : info.peaks) b.append(',').append(Math.round(p * 100));
        return b.toString();
    }

    static Info parse(String s) {
        try {
            String[] parts = s.split(",");
            float[] peaks = new float[parts.length - 1];
            for (int i = 1; i < parts.length; i++) peaks[i - 1] = Integer.parseInt(parts[i]) / 100f;
            return new Info(peaks, Long.parseLong(parts[0]));
        } catch (Exception e) { return null; }
    }

    /** "0:07", "1:42", "12:05". */
    static String clock(long ms) {
        long s = Math.max(0, (ms + 500) / 1000);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    private VoiceNotes() {}
}
