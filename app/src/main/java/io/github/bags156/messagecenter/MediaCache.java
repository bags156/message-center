package io.github.bags156.messagecenter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.webkit.MimeTypeMap;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Downloaded attachments, kept in the app's cache folder (Android may clear it when space
 * runs low; they download again when needed). Each attachment downloads once even if
 * several screens ask for it at the same time.
 */
final class MediaCache {
    private MediaCache() {}

    interface Done { void done(File file, String error); }

    private static final Map<String, List<Done>> waiting = new HashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 8, 48L << 20)) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };

    static File dir(Context context) {
        File dir = new File(context.getCacheDir(), "media");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** The cached file for an attachment, or for its small preview when thumb is true. */
    static File file(Context context, JSONObject media, boolean thumb) {
        String id = thumb ? PairedProtocol.string(media, "thumbnailMediaID") : PairedProtocol.string(media, "mediaID");
        // Attachments without a download ID yet (preview only, or sent inline) are named by message and part.
        if (id.isEmpty()) id = "msg:" + media.optString("_message") + ":" + media.optString("_index") + (thumb ? ":thumb" : "");
        String ext = thumb ? "jpg" : extension(media);
        return new File(dir(context), hash(id) + (thumb ? "-thumb." : ".") + ext);
    }

    static boolean hasThumbnail(JSONObject media) { return !PairedProtocol.string(media, "thumbnailMediaID").isEmpty(); }
    /** The full attachment can be downloaded now (or was sent inside the message itself). */
    static boolean hasFull(JSONObject media) {
        return (!PairedProtocol.string(media, "mediaID").isEmpty() && !PairedProtocol.string(media, "decryptionKey").isEmpty()) || !PairedProtocol.string(media, "mediaData").isEmpty();
    }
    /** Only a preview arrived; the phone sends the full file when asked. */
    static boolean previewOnly(JSONObject media) { return !hasFull(media) && hasThumbnail(media); }

    static String extension(JSONObject media) {
        String name = PairedProtocol.string(media, "mediaName");
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (!ext.isEmpty() && ext.length() <= 5) return ext;
        }
        String fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(PairedProtocol.string(media, "mimeType"));
        return fromMime == null ? "bin" : fromMime;
    }

    /** Downloads (or reuses) an attachment; the callback runs on the main thread. */
    static void fetch(Context context, JSONObject media, boolean thumb, Done done) {
        File target = file(context, media, thumb);
        if (target.isFile() && target.length() > 0) { done.done(target, ""); return; }
        String key = target.getName();
        synchronized (waiting) {
            List<Done> list = waiting.get(key);
            if (list != null) { list.add(done); return; }
            list = new ArrayList<>(); list.add(done); waiting.put(key, list);
        }
        String id = thumb ? PairedProtocol.string(media, "thumbnailMediaID") : PairedProtocol.string(media, "mediaID");
        String secret = thumb ? PairedProtocol.string(media, "thumbnailDecryptionKey") : PairedProtocol.string(media, "decryptionKey");
        String inline = thumb ? "" : PairedProtocol.string(media, "mediaData");
        if (!inline.isEmpty() && (id.isEmpty() || secret.isEmpty())) {
            // Small attachments can come inside the message itself.
            PairedService.executeMedia(c -> { byte[] data = android.util.Base64.decode(inline, android.util.Base64.DEFAULT);
                File part = new File(target.getPath() + ".part"); try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) { out.write(data); }
                if (!part.renameTo(target)) throw new Exception("There is no room to save this attachment."); return target.getPath(); }, (value, error) -> {
                List<Done> list; synchronized (waiting) { list = waiting.remove(key); } if (list == null) return;
                for (Done d : list) d.done(error.isEmpty() ? target : null, error); });
            return;
        }
        PairedService.executeMedia(c -> { c.downloadMedia(id, secret, target.getPath()); return target.getPath(); }, (value, error) -> {
            List<Done> list;
            synchronized (waiting) { list = waiting.remove(key); }
            if (list == null) return;
            for (Done d : list) d.done(error.isEmpty() ? target : null, error);
        });
    }

    /** An already-decoded image for this box, so redrawing a chat does not flash empty boxes. */
    static Bitmap peekCover(File file, int boxWidth, int boxHeight) { return memory.get(file.getName() + "@" + boxWidth + "x" + boxHeight); }

    /**
     * Decodes an image (or a video's first frame) just large enough to fill a box of this size
     * when center-cropped, never smaller in either direction, so it is never stretched up and
     * never decoded larger than needed.
     */
    static Bitmap decodeToCover(File file, int boxWidth, int boxHeight, boolean video) {
        String key = file.getName() + "@" + boxWidth + "x" + boxHeight;
        Bitmap cached = memory.get(key);
        if (cached != null) return cached;
        Bitmap bitmap = null;
        try {
            if (video) {
                int side = Math.max(boxWidth, boxHeight) * 2;
                try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
                    r.setDataSource(file.getPath());
                    bitmap = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side);
                }
            } else {
                bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                    int[] size = coverSize(info.getSize().getWidth(), info.getSize().getHeight(), boxWidth, boxHeight);
                    decoder.setTargetSize(size[0], size[1]);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
        if (bitmap != null) memory.put(key, bitmap);
        return bitmap;
    }

    /** The smallest size, keeping the image's shape, that still covers the box (or the original if it is already smaller). */
    static int[] coverSize(int width, int height, int boxWidth, int boxHeight) {
        if (width <= 0 || height <= 0) return new int[]{Math.max(1, width), Math.max(1, height)};
        float scale = Math.max(boxWidth / (float) width, boxHeight / (float) height);
        if (scale >= 1f) return new int[]{width, height};
        return new int[]{Math.max(1, (int) Math.ceil(width * scale)), Math.max(1, (int) Math.ceil(height * scale))};
    }

    /** An already-decoded preview, so redrawing a chat does not flash empty boxes. */
    static Bitmap peek(File file, int maxSide) { return memory.get(file.getName() + "@" + maxSide); }

    /**
     * Decodes a photo (JPEG, PNG, WebP, HEIC, GIF first frame) at most maxSide pixels on its
     * longest edge, or a video's first frame. Returns null if Android cannot read it.
     */
    static Bitmap decode(File file, int maxSide, boolean video) {
        String key = file.getName() + "@" + maxSide;
        Bitmap cached = memory.get(key);
        if (cached != null) return cached;
        Bitmap bitmap = null;
        try {
            if (video) {
                try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
                    r.setDataSource(file.getPath());
                    bitmap = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide);
                }
            } else {
                bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight(), longest = Math.max(w, h);
                    if (longest > maxSide) { float scale = maxSide / (float) longest; decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))); }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
        if (bitmap != null) memory.put(key, bitmap);
        return bitmap;
    }

    static String mime(JSONObject media) {
        String mime = PairedProtocol.string(media, "mimeType");
        if (!mime.isEmpty()) return mime;
        String fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(media));
        return fromExt == null ? "application/octet-stream" : fromExt;
    }

    static String hash(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(String.format(Locale.ROOT, "%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
