package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns a voice message into text with Android's on-device speech recognizer. The audio is
 * decoded on the phone and handed to the recognizer as a file; nothing is uploaded by this
 * app. Results are saved per message so each one is transcribed once.
 */
final class Transcriber {
    private Transcriber() {}

    interface Done { void done(String text, String error); }

    private static final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean running;
    private static final long TIMEOUT_MS = 3 * 60 * 1000;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("transcripts", Context.MODE_PRIVATE); }

    /** A saved transcript for this attachment, or null. */
    static String saved(Context context, String mediaID) { return mediaID.isEmpty() ? null : prefs(context).getString(mediaID, null); }

    static boolean available(Context context) { return SpeechRecognizer.isOnDeviceRecognitionAvailable(context); }

    /** Transcribes one audio file; the callback runs on the main thread. One transcription runs at a time. */
    static void transcribe(Context context, File audio, String mediaID, Done done) {
        String cached = saved(context, mediaID);
        if (cached != null) { done.done(cached, ""); return; }
        if (!available(context)) {
            done.done("", "This phone has no on-device speech recognition. Install or update \"Speech Recognition & Synthesis from Google\" in the Play Store, then try again.");
            return;
        }
        if (running) { done.done("", "Another voice message is being transcribed. Try again in a moment."); return; }
        running = true;
        File pcm = new File(new File(context.getCacheDir(), "outgoing"), MediaCache.hash(mediaID + audio.getName()) + ".pcm");
        decoder.execute(() -> {
            String problem = null;
            try { decode(audio, pcm); } catch (Exception e) { problem = e.getMessage() == null ? "This audio could not be read for transcription." : e.getMessage(); }
            String p = problem;
            main.post(() -> {
                if (p != null) { running = false; pcm.delete(); done.done("", p); return; }
                recognize(context.getApplicationContext(), pcm, mediaID, done);
            });
        });
    }

    /** Decodes any audio Android can play (AAC/M4A, AMR, MP3, Opus, WAV) to 16 kHz mono PCM. */
    private static void decode(File in, File out) throws Exception {
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            extractor.setDataSource(in.getPath());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { extractor.selectTrack(i); format = f; break; }
            }
            if (format == null) throw new Exception("This message has no audio Android can read.");
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > 20L * 60 * 1_000_000) throw new Exception("Voice messages over 20 minutes can't be transcribed here.");
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            Pcm.Resampler resampler = new Pcm.Resampler(rate);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (true) {
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
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)
                        throw new Exception("This audio format can't be transcribed here.");
                    resampler = new Pcm.Resampler(rate);
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        short[] samples = new short[info.size / 2];
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
                        os.write(Pcm.bytes(resampler.feed(samples, channels)));
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            extractor.release();
        }
        if (out.length() < Pcm.RATE / 2) throw new Exception("This voice message is too short to transcribe.");
    }

    private static void recognize(Context context, File pcm, String mediaID, Done done) {
        ParcelFileDescriptor fd;
        try { fd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (Exception e) { running = false; pcm.delete(); done.done("", "This audio could not be read for transcription."); return; }
        SpeechRecognizer recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, fd)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Pcm.RATE)
            // Keep listening through pauses until the whole file has been read.
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
        StringBuilder text = new StringBuilder();
        boolean[] finished = {false};
        Runnable[] timeout = new Runnable[1];
        java.util.function.BiConsumer<String, String> finish = (result, error) -> {
            if (finished[0]) return;
            finished[0] = true; running = false; main.removeCallbacks(timeout[0]);
            try { recognizer.destroy(); } catch (Exception ignored) {}
            try { fd.close(); } catch (Exception ignored) {}
            pcm.delete();
            if (error.isEmpty()) prefs(context).edit().putString(mediaID, result).apply();
            done.done(result, error);
        };
        timeout[0] = () -> finish.accept(text.toString().trim(), text.length() > 0 ? "" : "Transcription took too long. Try again.");
        recognizer.setRecognitionListener(new RecognitionListener() {
            private void add(Bundle results) {
                ArrayList<String> best = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (best != null && !best.isEmpty() && !best.get(0).trim().isEmpty()) { if (text.length() > 0) text.append(' '); text.append(best.get(0).trim()); }
            }
            @Override public void onSegmentResults(Bundle segment) { add(segment); }
            @Override public void onEndOfSegmentedSession() { finish.accept(text.toString().trim(), text.length() > 0 ? "" : "No speech was found in this voice message."); }
            @Override public void onResults(Bundle results) { add(results); finish.accept(text.toString().trim(), text.length() > 0 ? "" : "No speech was found in this voice message."); }
            @Override public void onError(int error) {
                if (text.length() > 0) { finish.accept(text.toString().trim(), ""); return; }
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                    try { recognizer.triggerModelDownload(intent); } catch (Exception ignored) {}
                }
                finish.accept("", message(error));
            }
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partial) {}
            @Override public void onEvent(int type, Bundle params) {}
        });
        main.postDelayed(timeout[0], TIMEOUT_MS);
        try { recognizer.startListening(intent); }
        catch (Exception e) { finish.accept("", "Speech recognition could not start on this phone."); }
    }

    static String message(int error) {
        return switch (error) {
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                "Your phone doesn't have offline speech recognition for " + Locale.getDefault().getDisplayLanguage() + " yet. It may download now; try again in a few minutes.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow Message Center to use the microphone. Android's speech recognizer requires it, even for messages.";
            case SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was found in this voice message.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "The speech recognizer is busy. Try again in a moment.";
            default -> "This voice message couldn't be transcribed (speech recognizer error " + error + ").";
        };
    }
}
