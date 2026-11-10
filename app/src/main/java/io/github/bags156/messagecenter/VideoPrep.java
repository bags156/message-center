package io.github.bags156.messagecenter;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;

import androidx.annotation.OptIn;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Gets a video ready to send: re-encoded with the phone's own video encoder (Media3 Transformer)
 * to the size VideoPlan picks. HDR video (most new phones, and iPhones) is tone-mapped to
 * ordinary colour so it doesn't look washed out on the other end. The picture keeps its
 * orientation; the sound is copied as it is when it can be. If the phone can't re-encode it, the
 * original is sent when it's small enough to go at all.
 */
@OptIn(markerClass = UnstableApi.class)
final class VideoPrep {
    interface Progress { void at(int percent); }

    /** The video to send (a new file next to the original if it was changed). Blocks; call off the main thread. */
    static PhotoPrep.Ready prepare(Context context, File in, String name, String mime, boolean mms, Progress progress) throws IOException {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (!m.startsWith("video/")) return new PhotoPrep.Ready(in, name, mime);
        VideoPlan.Source source = read(in, m);
        if (source == null) return new PhotoPrep.Ready(in, name, mime); // not a video this phone can read: as it is
        VideoPlan plan = VideoPlan.of(source, mms);
        if (plan.problem != null) throw new IOException(plan.problem);
        if (plan.asIs) return new PhotoPrep.Ready(in, name, mime);
        long limit = mms ? VideoPlan.MMS_BYTES : VideoPlan.RCS_LIMIT;
        String base = name.replaceAll("\\.[A-Za-z0-9]+$", "");
        File out = new File(in.getParentFile(), in.getName().replaceAll("\\.[A-Za-z0-9]+$", "") + "-send.mp4");
        long started = System.currentTimeMillis();
        PowerManager.WakeLock awake = null;
        try {
            // Keep going with the screen off: compressing a long video can take a minute or two.
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power != null) { awake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MessageCenter:video"); awake.setReferenceCounted(false); awake.acquire(30 * 60_000L); }
        } catch (Exception ignored) {}
        Outcome done = null;
        try {
            int mode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL;
            for (int attempt = 0; attempt < 4; attempt++) {
                done = export(context, in, out, plan, mode, mms, progress);
                if (done.error != null) {
                    // Some phones tone-map HDR in the decoder instead: try that once.
                    if (source.hdr && mode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) { mode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC; continue; }
                    break;
                }
                if (out.length() > 0 && out.length() <= limit) break;
                Diagnostics.note("Video came out " + out.length() / 1024 + " KB at " + plan + "; trying smaller");
                plan = plan.smaller(source, mms, out.length());
            }
        } finally {
            if (awake != null) try { awake.release(); } catch (Exception ignored) {}
        }
        long took = System.currentTimeMillis() - started;
        String from = source.width + "×" + source.height + (source.frameRate > 0 ? " " + Math.round(source.frameRate) + " fps" : "")
                + (source.hdr ? " HDR" : "") + " " + source.video + " " + in.length() / 1024 + " KB";
        boolean made = done != null && done.error == null && out.length() > 0 && out.length() <= limit;
        if (made && !mms && out.length() >= in.length() && in.length() <= limit && source.container.equals("video/mp4") && !source.hdr) {
            // Came out no smaller: the original is better.
            Diagnostics.note("Video for RCS kept as it is (" + from + "): re-encoding made it " + out.length() / 1024 + " KB");
            out.delete();
            return new PhotoPrep.Ready(in, name, mime);
        }
        if (made) {
            Diagnostics.note("Video prepared for " + (mms ? "MMS" : "RCS") + ": " + from + " → " + done.width + "×" + done.height
                    + " " + out.length() / 1024 + " KB (" + plan + ") in " + took + " ms" + (done.encoder.isEmpty() ? "" : " with " + done.encoder));
            return new PhotoPrep.Ready(out, base + ".mp4", "video/mp4");
        }
        String why = done == null ? "not started" : done.error != null ? done.error : "came out " + out.length() / 1024 + " KB";
        Diagnostics.note("Video couldn't be prepared for " + (mms ? "MMS" : "RCS") + " (" + from + "): " + why + " after " + took + " ms");
        out.delete();
        if (in.length() <= limit) return new PhotoPrep.Ready(in, name, mime); // it may still go as it is
        throw new IOException(mms ? "This video couldn't be made small enough for a picture message (MMS). Try a shorter clip, or send it in an RCS chat."
                : "This video is over 100 MB and couldn't be made smaller on this phone. Try a shorter clip.");
    }

    /** What's known about the video, or null if it has no picture this phone can read. */
    static VideoPlan.Source read(File file, String mime) {
        VideoPlan.Source s = new VideoPlan.Source();
        s.container = mime == null ? "" : mime.toLowerCase(Locale.ROOT); s.bytes = file.length();
        MediaExtractor x = new MediaExtractor();
        try {
            x.setDataSource(file.getPath());
            for (int i = 0; i < x.getTrackCount(); i++) {
                MediaFormat f = x.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m == null) continue;
                if (f.containsKey(MediaFormat.KEY_DURATION)) s.durationMs = Math.max(s.durationMs, f.getLong(MediaFormat.KEY_DURATION) / 1000);
                if (m.startsWith("video/") && s.video.isEmpty()) {
                    s.video = m; s.width = f.getInteger(MediaFormat.KEY_WIDTH, 0); s.height = f.getInteger(MediaFormat.KEY_HEIGHT, 0);
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) { Number n = f.getNumber(MediaFormat.KEY_FRAME_RATE); if (n != null) s.frameRate = n.floatValue(); }
                    int transfer = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0);
                    s.hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG || m.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION);
                } else if (m.startsWith("audio/") && s.audio.isEmpty()) {
                    s.audio = m; s.channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0);
                }
            }
        } catch (Exception e) {
            return null;
        } finally {
            x.release();
        }
        if (s.video.isEmpty() || s.width <= 0 || s.height <= 0) return null;
        if (s.durationMs <= 0 || s.frameRate <= 0) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(file.getPath());
                if (s.durationMs <= 0) s.durationMs = number(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                long frames = number(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
                if (s.frameRate <= 0 && frames > 0 && s.durationMs > 0) s.frameRate = frames * 1000f / s.durationMs;
            } catch (Exception ignored) {
            } finally {
                try { r.release(); } catch (Exception ignored) {}
            }
        }
        return s;
    }

    private static long number(String s) { try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; } }

    private static final class Outcome { volatile String error; volatile String encoder = ""; volatile int width, height; }

    /** One re-encode with Media3 Transformer, on a thread of its own (it calls back on the thread it's started from). */
    private static Outcome export(Context context, File in, File out, VideoPlan plan, int hdrMode, boolean mms, Progress progress) {
        Outcome result = new Outcome();
        out.delete();
        HandlerThread thread = new HandlerThread("video-prep");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        CountDownLatch finished = new CountDownLatch(1);
        Transformer[] running = new Transformer[1];
        handler.post(() -> {
            try {
                List<Effect> picture = new ArrayList<>();
                if (plan.frameRate > 0) picture.add(FrameDropEffect.createDefaultFrameDropEffect(plan.frameRate));
                if (plan.shortSide > 0) picture.add(Presentation.createForShortSide(plan.shortSide).copyWithUnsetSideRoundedTo(2)); // encoders want even sizes
                List<AudioProcessor> sound = new ArrayList<>();
                if (plan.mono) {
                    ChannelMixingAudioProcessor mix = new ChannelMixingAudioProcessor();
                    for (int n = 1; n <= 8; n++) { float[] each = new float[n]; java.util.Arrays.fill(each, 1f / n); mix.putChannelMixingMatrix(new ChannelMixingMatrix(n, 1, each)); }
                    sound.add(mix);
                }
                // MMS has a hard size limit, so the encoder is asked to keep strictly to the bitrate (constant bitrate).
                VideoEncoderSettings.Builder picked = new VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate);
                if (mms && constantBitrate()) picked.setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                DefaultEncoderFactory.Builder encoders = new DefaultEncoderFactory.Builder(context).setEnableFallback(true)
                        .setRequestedVideoEncoderSettings(picked.build());
                if (plan.audioBitrate > 0) encoders.setRequestedAudioEncoderSettings(new AudioEncoderSettings.Builder().setBitrate(plan.audioBitrate).build());
                EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(in))).setEffects(new Effects(sound, picture)).build();
                Composition composition = new Composition.Builder(new EditedMediaItemSequence.Builder(item).build()).setHdrMode(hdrMode).build();
                Transformer transformer = new Transformer.Builder(context).setLooper(thread.getLooper())
                        .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(encoders.build())
                        .addListener(new Transformer.Listener() {
                            @Override public void onCompleted(Composition c, ExportResult r) {
                                result.width = r.width; result.height = r.height; result.encoder = r.videoEncoderName == null ? "" : r.videoEncoderName;
                                finished.countDown();
                            }
                            @Override public void onError(Composition c, ExportResult r, ExportException e) {
                                android.util.Log.w("VideoPrep", "Export failed", e);
                                result.error = e.getErrorCodeName() + causes(e); finished.countDown();
                            }
                        }).build();
                running[0] = transformer;
                transformer.start(composition, out.getPath());
                ProgressHolder holder = new ProgressHolder();
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (finished.getCount() == 0) return;
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE && progress != null) progress.at(holder.progress);
                        handler.postDelayed(this, 400);
                    }
                }, 400);
            } catch (Exception e) {
                android.util.Log.w("VideoPrep", "Export couldn't start", e);
                result.error = e.getClass().getSimpleName() + causes(e);
                finished.countDown();
            }
        });
        try {
            if (!finished.await(25, TimeUnit.MINUTES)) { result.error = "took over 25 minutes"; handler.post(() -> { if (running[0] != null) running[0].cancel(); }); }
        } catch (InterruptedException e) {
            result.error = "stopped";
            handler.post(() -> { if (running[0] != null) running[0].cancel(); });
            Thread.currentThread().interrupt();
        } finally {
            thread.quitSafely();
        }
        return result;
    }

    /** Whether one of this phone's H.264 encoders can keep to a constant bitrate (asking one that can't fails). */
    private static boolean constantBitrate() {
        try {
            for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes())
                    if (type.equalsIgnoreCase(MimeTypes.VIDEO_H264) && info.getCapabilitiesForType(type).getEncoderCapabilities()
                            .isBitrateModeSupported(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** What went wrong underneath, for "Report a problem" (codec and format names; no message content). */
    private static String causes(Throwable e) {
        StringBuilder b = new StringBuilder();
        for (Throwable t = e.getCause(); t != null && b.length() < 400; t = t.getCause())
            b.append(" ← ").append(t.getClass().getSimpleName()).append(t.getMessage() == null ? "" : ": " + t.getMessage());
        return b.length() > 400 ? b.substring(0, 400) : b.toString();
    }

    private VideoPrep() {}
}
