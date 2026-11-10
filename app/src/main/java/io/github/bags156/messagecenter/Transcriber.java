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
 * decoded on the phone, cut at its pauses, and played to the recognizer a piece at a time, at
 * the pace of speech; nothing is uploaded by this app. Results are saved per message so each one is transcribed once.
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
     * Recognizes a voice message piece by piece (it's cut at pauses; see Pcm.speech). Each piece
     * is played to the recognizer like a microphone would (see recognize). If the recognizer stops
     * partway through a piece, it's started again from a little before where it stopped. The first
     * session asks for punctuation and capitalization, unmasked words, and the chat's names; if the
     * recognizer rejects any of that, the rest go without.
     */
    private static final class Job {
        private static final long BACK_UP = Pcm.RATE * 2;            // 1 s of audio, in bytes
        private static final long PADDING = Pcm.RATE * 2;            // the silence after each piece (Pcm.split)
        private static final int MAX_RESTARTS = 8;
        private final Context context;
        private final List<File> parts;
        private final String mediaID;
        private final List<String> names;
        private final Progress progress;
        private final Done done;
        private final List<String> pieces = new ArrayList<>();
        private final boolean english = Locale.getDefault().getLanguage().equals("en");
        private boolean extras = true;
        private int at, restarts;
        private long from;

        Job(Context context, List<File> parts, String mediaID, List<String> names, Progress progress, Done done) {
            this.context = context; this.parts = parts; this.mediaID = mediaID; this.names = names; this.progress = progress; this.done = done;
        }

        void next() {
            if (at >= parts.size()) { finish(""); return; }
            if (progress != null) progress.at(at, parts.size());
            File part = parts.get(at);
            recognize(context, part, from, names, extras, (heard, error, reached) -> {
                if (heard.isEmpty() && error >= 0) {
                    if (extras && retryable(error)) { extras = false; next(); return; }
                    if (fatal(error)) { finish(message(error)); return; }
                    // Otherwise this stretch was a cough or a mumble; carry on.
                }
                pieces.addAll(heard);
                // Stopped with speech still to come: go again from a second before it stopped.
                long rest = part.length() - PADDING - reached, again = Math.max(from + Pcm.RATE, reached - BACK_UP);
                if (rest > Pcm.RATE && restarts < MAX_RESTARTS && again < part.length() - PADDING) { from = again; restarts++; next(); return; }
                part.delete();
                at++; from = 0; restarts = 0;
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

    /** What one recognizer session heard; error is -1 if it heard something; reached is how far into the file it had been played. */
    private interface Heard { void heard(List<String> pieces, int error, long reached); }

    private static final ExecutorService player = Executors.newCachedThreadPool();

    /**
     * Runs the recognizer over one piece of audio, starting from byte offset from. The audio is
     * played into a pipe at the pace of speech, starting once the recognizer says it's listening:
     * given the whole file at once, a recognizer can skip ahead and drop what it wasn't ready for,
     * which left only a stretch from the middle of a long message.
     */
    private static void recognize(Context context, File pcm, long from, List<String> names, boolean extras, Heard then) {
        ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (Exception e) { then.heard(new ArrayList<>(), SpeechRecognizer.ERROR_CLIENT, pcm.length()); return; }
        SpeechRecognizer recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
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
        java.util.concurrent.atomic.AtomicBoolean playing = new java.util.concurrent.atomic.AtomicBoolean(), stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong reached = new java.util.concurrent.atomic.AtomicLong(from);
        Runnable[] timeout = new Runnable[1], fallbackStart = new Runnable[1];
        java.util.function.IntConsumer finish = error -> {
            if (finished[0]) return;
            finished[0] = true; stop.set(true);
            main.removeCallbacks(timeout[0]); main.removeCallbacks(fallbackStart[0]);
            try { recognizer.destroy(); } catch (Exception ignored) {}
            try { pipe[0].close(); } catch (Exception ignored) {}
            if (!partial[0].isEmpty()) heard.add(partial[0]);
            // Not played at all (it never got going): count the piece as heard, so it isn't retried forever.
            then.heard(heard, heard.isEmpty() ? error : -1, playing.get() ? reached.get() : pcm.length());
        };
        Runnable play = () -> {
            if (!playing.compareAndSet(false, true)) return;
            player.execute(() -> {
                byte[] chunk = new byte[Pcm.RATE / 10 * 2]; // 100 ms
                try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(pcm, "r");
                     java.io.OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    out.write(new byte[Pcm.RATE / 4 * 2]); // a quarter second of silence to settle in
                    in.seek(from);
                    long started = android.os.SystemClock.elapsedRealtime(), sent = 0;
                    int n;
                    while (!stop.get() && (n = in.read(chunk)) > 0) {
                        out.write(chunk, 0, n);
                        sent += n; reached.addAndGet(n);
                        long due = started + sent * 1000 / (Pcm.RATE * 2);
                        long wait = due - android.os.SystemClock.elapsedRealtime();
                        if (wait > 0) Thread.sleep(wait);
                    }
                } catch (Exception ignored) {
                    // The recognizer closed its end: it has finished with this piece.
                }
            });
        };
        timeout[0] = () -> finish.accept(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
        // Some recognizers don't announce they're listening; start playing anyway after a moment.
        fallbackStart[0] = play;
        recognizer.setRecognitionListener(new RecognitionListener() {
            private void add(Bundle results) {
                ArrayList<String> best = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (best != null && !best.isEmpty() && !best.get(0).trim().isEmpty()) heard.add(best.get(0).trim());
                partial[0] = "";
            }
            @Override public void onReadyForSpeech(Bundle params) { main.removeCallbacks(fallbackStart[0]); play.run(); }
            @Override public void onSegmentResults(Bundle segment) { add(segment); }
            @Override public void onEndOfSegmentedSession() { finish.accept(SpeechRecognizer.ERROR_NO_MATCH); }
            @Override public void onResults(Bundle results) { add(results); finish.accept(SpeechRecognizer.ERROR_NO_MATCH); }
            @Override public void onError(int error) {
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                    try { recognizer.triggerModelDownload(intent); } catch (Exception ignored) {}
                }
                finish.accept(error);
            }
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
        // Played in real time, so the session lasts about as long as the piece, plus time to finish.
        main.postDelayed(timeout[0], (pcm.length() - from) * 1000 / (Pcm.RATE * 2) + 20_000);
        main.postDelayed(fallbackStart[0], 1500);
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
