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
 * A long one that has to stop partway (the recognizer stays busy, or Android ends it) keeps what
 * it has, says where it got to, and carries on from there next time; it's never saved as if it
 * were the whole message.
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

    /** Drops a saved transcript (whole or partway), to transcribe the message again. */
    static void forget(Context context, String mediaID) { if (!mediaID.isEmpty()) prefs(context).edit().remove(mediaID).remove(mediaID + PART).remove(mediaID + UNTIL).remove(mediaID + TOTAL).remove(mediaID + FOLDED).apply(); }

    private static final String FOLDED = "|folded";
    /** Whether you folded this transcript to one line. */
    static boolean folded(Context context, String mediaID) { return !mediaID.isEmpty() && prefs(context).getBoolean(mediaID + FOLDED, false); }
    static void fold(Context context, String mediaID, boolean on) { if (mediaID.isEmpty()) return; SharedPreferences.Editor e = prefs(context).edit(); if (on) e.putBoolean(mediaID + FOLDED, true); else e.remove(mediaID + FOLDED); e.apply(); }

    private static final String PART = "|part", UNTIL = "|until", TOTAL = "|total";
    /** Said where a stretch couldn't be made out, instead of leaving it out. */
    static final String UNCLEAR = "[unclear]";

    /** A transcription that stopped partway: the text so far, and how far into the message (and of how long) it got. */
    static final class Partial {
        final String text; final long untilMs, totalMs;
        Partial(String text, long untilMs, long totalMs) { this.text = text; this.untilMs = untilMs; this.totalMs = totalMs; }
    }
    static Partial partial(Context context, String mediaID) {
        if (mediaID.isEmpty() || saved(context, mediaID) != null) return null;
        SharedPreferences p = prefs(context);
        if (!p.contains(mediaID + UNTIL)) return null;
        return new Partial(p.getString(mediaID + PART, ""), p.getLong(mediaID + UNTIL, 0), p.getLong(mediaID + TOTAL, 0));
    }

    /** "6:12". */
    static String clock(long ms) { long s = Math.max(0, ms) / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60); }

    static boolean available(Context context) { return SpeechRecognizer.isOnDeviceRecognitionAvailable(context); }

    /** How far along a transcription is: done of total pieces. Runs on the main thread. */
    interface Progress { void at(int done, int total); }

    /**
     * Transcribes one audio file; the callbacks run on the main thread. One transcription runs at
     * a time. names (people in the chat) are words the recognizer should expect.
     */
    static void transcribe(Context context, File audio, String mediaID, List<String> names, boolean onScreen, Progress progress, Done done) {
        String cached = saved(context, mediaID);
        if (cached != null) { done.done(cached, ""); return; }
        if (!available(context)) {
            done.done("", "This phone has no on-device speech recognition. Install or update \"Speech Recognition & Synthesis from Google\" in the Play Store, then try again.");
            return;
        }
        if (running) { done.done("", "Another voice message is being transcribed. Try again in a moment."); return; }
        running = true;
        // So it can finish if you leave the app (only possible to start while it's on screen).
        if (onScreen) TranscribeService.start(context);
        File dir = new File(context.getCacheDir(), "outgoing"), pcm = new File(dir, MediaCache.hash(mediaID + audio.getName()) + ".pcm");
        decoder.execute(() -> {
            String problem = null;
            List<File> parts = new ArrayList<>();
            List<long[]> times = new ArrayList<>();
            long total = 0;
            try { decode(audio, pcm); total = pcm.length() * 1000 / (Pcm.RATE * 2L); parts = Pcm.split(pcm, dir, pcm.getName(), times); }
            catch (Exception e) { problem = e.getMessage() == null ? "This audio could not be read for transcription." : e.getMessage(); }
            finally { pcm.delete(); }
            String p = problem;
            List<File> pieces = parts;
            long length = total;
            main.post(() -> {
                if (p != null) { stopped(context); for (File f : pieces) f.delete(); done.done("", p); return; }
                if (pieces.isEmpty()) { stopped(context); done.done("", "No speech was found in this voice message."); return; }
                new Job(context.getApplicationContext(), pieces, times, length, mediaID, names, progress, done).start();
            });
        });
    }

    private static void stopped(Context context) { running = false; TranscribeService.stop(context); }

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
     * is played to the recognizer like a microphone would, but faster (see recognize, Speed); a
     * piece that comes back with too few words is played again slower. If the recognizer stops
     * partway through a piece, it's started again from a little before where it stopped. The first
     * session asks for punctuation and capitalization, unmasked words, and the chat's names; if the
     * recognizer rejects any of that, the rest go without.
     */
    private static final class Job {
        /** Waits before trying a piece again while the recognizer says it's busy (or asked too often): 2 s up to 40 s. */
        private static final long[] WAITS = {2_000, 5_000, 10_000, 20_000, 40_000};
        /** A breath between pieces, so the last session is gone before the next starts (the recognizer can say it's busy otherwise). */
        private static final long BETWEEN = 250;
        private static final long BACK_UP = Pcm.RATE * 2;            // 1 s of audio, in bytes
        private static final long PADDING = Pcm.RATE * 2;            // the silence after each piece (Pcm.split)
        private static final long LEAD = Pcm.RATE / 5 * 2;           // the silence before it
        private static final int MAX_RESTARTS = 8;
        private final Context context;
        private final List<File> parts;
        private final List<long[]> times;
        private final long totalMs;
        private final String mediaID;
        private final List<String> names;
        private final Progress progress;
        private final Done done;
        private final List<String> pieces = new ArrayList<>();
        private final boolean english = Locale.getDefault().getLanguage().equals("en");
        private final List<String> heardHere = new ArrayList<>();
        private boolean extras = true, slowedDown, retried;
        private int at, restarts, waits;
        private long from;
        private float speed;

        Job(Context context, List<File> parts, List<long[]> times, long totalMs, String mediaID, List<String> names, Progress progress, Done done) {
            this.context = context; this.parts = parts; this.times = times; this.totalMs = totalMs; this.mediaID = mediaID; this.names = names; this.progress = progress; this.done = done;
            speed = Speed.now(prefs(context));
        }

        /** Carries on from where an earlier try stopped, if one did; otherwise from the start. */
        void start() {
            Partial was = partial(context, mediaID);
            if (was != null && was.untilMs > 0) {
                if (!was.text.isEmpty()) pieces.add(was.text);
                while (at < parts.size() && at < times.size() && times.get(at)[1] <= was.untilMs) { parts.get(at).delete(); at++; }
                Diagnostics.note("Transcribing: carrying on from " + clock(was.untilMs) + " of " + clock(totalMs));
            }
            next();
        }

        /** The same piece again, after a wait. */
        private void again(long waitMs) { main.postDelayed(this::next, Math.max(BETWEEN, waitMs)); }

        /** Where it's got to, kept as it goes: if Android ends it, the next try carries on from here. */
        private void keep() {
            long until = at > 0 && at - 1 < times.size() ? times.get(at - 1)[1] : 0;
            prefs(context).edit().putString(mediaID + PART, TranscriptText.join(pieces, english)).putLong(mediaID + UNTIL, until).putLong(mediaID + TOTAL, totalMs).apply();
        }

        void next() {
            if (at >= parts.size()) { finish(""); return; }
            if (progress != null) progress.at(at, parts.size());
            TranscribeService.progress(context, at, parts.size());
            File part = parts.get(at);
            double seconds = (part.length() - LEAD - PADDING) / (double) (Pcm.RATE * 2);
            recognize(context, part, from, speed, names, extras, (heard, error, reached) -> {
                if (heard.isEmpty() && error >= 0) {
                    if (extras && retryable(error)) { extras = false; again(0); return; }
                    // Busy, or asked too often: wait and try the same piece again, rather than stop.
                    if (busy(error)) {
                        if (waits < WAITS.length) { Diagnostics.note("Transcribing: recognizer busy at piece " + (at + 1) + " of " + parts.size() + ", waiting"); again(WAITS[waits++]); return; }
                        finish(message(error)); return;
                    }
                    if (fatal(error)) { finish(message(error)); return; }
                    // Nothing came back for a stretch of speech: a fresh session often hears it.
                    if (heardHere.isEmpty() && !retried && seconds >= 1.5) { retried = true; Diagnostics.note("Transcribing: nothing back for piece " + (at + 1) + " (error " + error + "), trying it again"); again(600); return; }
                }
                waits = 0;
                heardHere.addAll(heard);
                // Stopped with speech still to come: go again from a second before it stopped.
                long rest = part.length() - PADDING - reached, resume = Math.max(from + Pcm.RATE, reached - BACK_UP);
                if (rest > Pcm.RATE && restarts < MAX_RESTARTS && resume < part.length() - PADDING) { from = resume; restarts++; again(BETWEEN); return; }
                // Far fewer words than the piece's length suggests: the recognizer couldn't keep up. Redo it slower.
                if (speed > 1 && Speed.thin(heardHere, seconds)) {
                    speed = Speed.slower(prefs(context), speed); slowedDown = true;
                    heardHere.clear(); from = 0; restarts = 0; again(BETWEEN); return;
                }
                // Two seconds of speech and still nothing: say so, rather than leave it out.
                if (heardHere.isEmpty() && seconds >= 2) { pieces.add(UNCLEAR); Diagnostics.note("Transcribing: piece " + (at + 1) + " of " + parts.size() + " couldn't be made out"); }
                pieces.addAll(heardHere); heardHere.clear();
                part.delete();
                at++; from = 0; restarts = 0; retried = false;
                keep();
                again(BETWEEN);
            });
        }

        private void finish(String error) {
            stopped(context);
            for (File f : parts) f.delete();
            String text = TranscriptText.join(pieces, english);
            long until = at > 0 && at - 1 < times.size() ? times.get(at - 1)[1] : 0;
            if (!error.isEmpty() && at < parts.size() && until > 0) {
                // Stopped partway: what it has is kept as partway (never as the whole message), to carry on from next time.
                keep();
                Diagnostics.note("Transcribing: stopped at " + clock(until) + " of " + clock(totalMs) + " (piece " + (at + 1) + " of " + parts.size() + ")");
                done.done(text, "Transcription stopped at " + clock(until) + " of " + clock(totalMs) + ". " + error + " Tap “Finish transcribing” to carry on from there.");
                return;
            }
            if (!error.isEmpty() && text.isEmpty()) { done.done("", error); return; }
            if (text.isEmpty()) { done.done("", "No speech was found in this voice message."); return; }
            prefs(context).edit().putString(mediaID, text).remove(mediaID + PART).remove(mediaID + UNTIL).remove(mediaID + TOTAL).apply();
            // It all went through at this speed: try a step faster next time (never one that's failed).
            if (!slowedDown && parts.size() > 0) Speed.worked(prefs(context), speed);
            done.done(text, "");
        }
    }

    /** What one recognizer session heard; error is -1 if it heard something; reached is how far into the file it had been played. */
    private interface Heard { void heard(List<String> pieces, int error, long reached); }

    private static final ExecutorService player = Executors.newCachedThreadPool();

    /**
     * Runs the recognizer over one piece of audio, starting from byte offset from. The audio is
     * played into a pipe at a steady pace (speed times real time), starting once the recognizer
     * says it's listening: given the whole file at once, a recognizer can skip ahead and drop what
     * it wasn't ready for, which left only a stretch from the middle of a long message.
     */
    private static void recognize(Context context, File pcm, long from, float speed, List<String> names, boolean extras, Heard then) {
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
                        long due = started + Math.round(sent * 1000 / (Pcm.RATE * 2 * (double) speed));
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
        // Played at a steady pace, so the session lasts about as long as that takes, plus time to finish.
        main.postDelayed(timeout[0], Math.round((pcm.length() - from) * 1000 / (Pcm.RATE * 2 * (double) speed)) + 20_000);
        main.postDelayed(fallbackStart[0], 1500);
        try { recognizer.startListening(intent); }
        catch (Exception e) { finish.accept(SpeechRecognizer.ERROR_CLIENT); }
    }

    /** Errors that end the whole transcription: nothing more will work until something on the phone changes. */
    private static boolean fatal(int error) {
        return error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED;
    }

    /** The recognizer is busy or has been asked too often: worth waiting for, not giving up on. */
    private static boolean busy(int error) {
        return error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS;
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
