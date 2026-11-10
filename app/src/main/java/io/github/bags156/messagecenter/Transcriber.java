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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns a voice message into text with Android's on-device speech recognizer. The audio is
 * decoded on the phone, cut at its pauses, and handed to the recognizer a piece at a time;
 * nothing is uploaded by this app. Results are saved per message so each one is transcribed once.
 */
final class Transcriber {
    private Transcriber() {}

    interface Done { void done(String text, String error); }

    private static final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean running;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("transcripts", Context.MODE_PRIVATE); }

    /** A saved transcript for this attachment, or null. */
    static String saved(Context context, String mediaID) { return mediaID.isEmpty() ? null : prefs(context).getString(mediaID, null); }

    /** Drops a saved transcript, to transcribe the message again. */
    static void forget(Context context, String mediaID) { if (!mediaID.isEmpty()) prefs(context).edit().remove(mediaID).apply(); }

    static boolean available(Context context) { return SpeechRecognizer.isOnDeviceRecognitionAvailable(context); }

    /** How far along a transcription is: done of total pieces. Runs on the main thread. */
    interface Progress { void at(int done, int total); }

    /**
     * Transcribes one audio file; the callbacks run on the main thread. One transcription runs at
     * a time. names (people in the chat) are words the recognizer should expect.
     */
    static void transcribe(Context context, File audio, String mediaID, List<String> names, Progress progress, Done done) {
        String cached = saved(context, mediaID);
        if (cached != null) { done.done(cached, ""); return; }
        if (!available(context)) {
            done.done("", "This phone has no on-device speech recognition. Install or update \"Speech Recognition & Synthesis from Google\" in the Play Store, then try again.");
            return;
        }
        if (running) { done.done("", "Another voice message is being transcribed. Try again in a moment."); return; }
        running = true;
        File dir = new File(context.getCacheDir(), "outgoing"), pcm = new File(dir, MediaCache.hash(mediaID + audio.getName()) + ".pcm");
        decoder.execute(() -> {
            String problem = null;
            List<File> parts = new ArrayList<>();
            try { decode(audio, pcm); parts = Pcm.split(pcm, dir, pcm.getName()); }
            catch (Exception e) { problem = e.getMessage() == null ? "This audio could not be read for transcription." : e.getMessage(); }
            finally { pcm.delete(); }
            String p = problem;
            List<File> pieces = parts;
            main.post(() -> {
                if (p != null) { running = false; for (File f : pieces) f.delete(); done.done("", p); return; }
                if (pieces.isEmpty()) { running = false; done.done("", "No speech was found in this voice message."); return; }
                new Job(context.getApplicationContext(), pieces, mediaID, names, progress, done).next();
            });
        });
    }

    /**
     * Decodes any audio Android can play (AAC/M4A, AMR, MP3, Opus, WAV) to 16 kHz mono PCM, with
     * rumble filtered out and a quiet recording turned up.
     */
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
            Pcm.HighPass rumble = new Pcm.HighPass();
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
                    os.write(Pcm.bytes(rumble.apply(resampler.flush())));
                    resampler = new Pcm.Resampler(rate);
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        short[] samples = new short[info.size / 2];
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
                        os.write(Pcm.bytes(rumble.apply(resampler.feed(samples, channels))));
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            os.write(Pcm.bytes(rumble.apply(resampler.flush())));
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            extractor.release();
        }
        if (out.length() < Pcm.RATE / 2) throw new Exception("This voice message is too short to transcribe.");
        Pcm.normalize(out);
    }

    /**
     * Recognizes a voice message piece by piece (it's cut at pauses; see Pcm.speech), since a
     * phone's recognizer often stops listening at the first long pause. The first piece asks for
     * punctuation and capitalization, unmasked words, and the chat's names; if the recognizer
     * rejects any of that, the rest go without.
     */
    private static final class Job {
        private final Context context;
        private final List<File> parts;
        private final String mediaID;
        private final List<String> names;
        private final Progress progress;
        private final Done done;
        private final List<String> pieces = new ArrayList<>();
        private final boolean english = Locale.getDefault().getLanguage().equals("en");
        private boolean extras = true;
        private int at;

        Job(Context context, List<File> parts, String mediaID, List<String> names, Progress progress, Done done) {
            this.context = context; this.parts = parts; this.mediaID = mediaID; this.names = names; this.progress = progress; this.done = done;
        }

        void next() {
            if (at >= parts.size()) { finish(""); return; }
            if (progress != null) progress.at(at, parts.size());
            File part = parts.get(at);
            recognize(context, part, names, extras, (heard, error) -> {
                if (heard.isEmpty() && error >= 0) {
                    if (extras && retryable(error)) { extras = false; next(); return; }
                    if (fatal(error) || error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && pieces.isEmpty() && at == parts.size() - 1) {
                        finish(message(error)); return;
                    }
                    // Otherwise this piece was a cough or a mumble; carry on with the rest.
                }
                pieces.addAll(heard);
                part.delete();
                at++;
                next();
            });
        }

        private void finish(String error) {
            running = false;
            for (File f : parts) f.delete();
            String text = TranscriptText.join(pieces, english);
            if (!error.isEmpty() && text.isEmpty()) { done.done("", error); return; }
            if (text.isEmpty()) { done.done("", "No speech was found in this voice message."); return; }
            prefs(context).edit().putString(mediaID, text).apply();
            done.done(text, "");
        }
    }

    private interface Heard { void heard(List<String> pieces, int error); }

    /** Runs the recognizer over one piece of audio. Every result it gives for the piece is collected; error is -1 if none. */
    private static void recognize(Context context, File pcm, List<String> names, boolean extras, Heard then) {
        ParcelFileDescriptor fd;
        try { fd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (Exception e) { then.heard(new ArrayList<>(), SpeechRecognizer.ERROR_CLIENT); return; }
        SpeechRecognizer recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, fd)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Pcm.RATE)
            // Where the recognizer supports it, keep listening through any pause left in the piece.
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        if (extras) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                // A friend's voice message should read the way they said it, not with words starred out.
                .putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false);
            if (names != null && !names.isEmpty()) intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, new ArrayList<>(names));
        }
        List<String> heard = new ArrayList<>();
        // The latest guess for speech not finished yet: used if the recognizer stops without a final result.
        String[] partial = {""};
        boolean[] finished = {false};
        Runnable[] timeout = new Runnable[1];
        java.util.function.IntConsumer finish = error -> {
            if (finished[0]) return;
            finished[0] = true; main.removeCallbacks(timeout[0]);
            try { recognizer.destroy(); } catch (Exception ignored) {}
            try { fd.close(); } catch (Exception ignored) {}
            if (!partial[0].isEmpty()) heard.add(partial[0]);
            then.heard(heard, heard.isEmpty() ? error : -1);
        };
        timeout[0] = () -> finish.accept(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
        recognizer.setRecognitionListener(new RecognitionListener() {
            private void add(Bundle results) {
                ArrayList<String> best = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (best != null && !best.isEmpty() && !best.get(0).trim().isEmpty()) heard.add(best.get(0).trim());
                partial[0] = "";
            }
            @Override public void onSegmentResults(Bundle segment) { add(segment); }
            @Override public void onEndOfSegmentedSession() { finish.accept(SpeechRecognizer.ERROR_NO_MATCH); }
            @Override public void onResults(Bundle results) { add(results); finish.accept(SpeechRecognizer.ERROR_NO_MATCH); }
            @Override public void onError(int error) {
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                    try { recognizer.triggerModelDownload(intent); } catch (Exception ignored) {}
                }
                finish.accept(error);
            }
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle results) {
                ArrayList<String> best = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (best != null && !best.isEmpty() && !best.get(0).trim().isEmpty()) partial[0] = best.get(0).trim();
            }
            @Override public void onEvent(int type, Bundle params) {}
        });
        // A piece is at most about 16 seconds; the recognizer gets several times that.
        main.postDelayed(timeout[0], Math.max(20_000, pcm.length() / (Pcm.RATE * 2) * 4000));
        try { recognizer.startListening(intent); }
        catch (Exception e) { finish.accept(SpeechRecognizer.ERROR_CLIENT); }
    }

    /** Errors that end the whole transcription: nothing more will work until something on the phone changes. */
    private static boolean fatal(int error) {
        return error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS;
    }

    /** Errors that might come from the extra requests (formatting, names) rather than the audio or the phone's setup. */
    private static boolean retryable(int error) {
        return error >= 0 && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && error != SpeechRecognizer.ERROR_NO_MATCH
            && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            && error != SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED && error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            && error != SpeechRecognizer.ERROR_TOO_MANY_REQUESTS;
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
