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

    /** Pictures with a see-through background (Genmoji, stickers): shown on their own, like a big emoji. */
    private static final java.util.Set<String> stickers = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    static boolean isSticker(File file) { return stickers.contains(file.getName()); }
    /** See-through at the corners: a cut-out picture rather than a photo. */
    static boolean sticker(Bitmap b) {
        if (b == null || !b.hasAlpha() || b.getWidth() < 2 || b.getHeight() < 2) return false;
        int w = b.getWidth() - 1, h = b.getHeight() - 1, clear = 0;
        for (int[] p : new int[][]{{0, 0}, {w, 0}, {0, h}, {w, h}}) if ((b.getPixel(p[0], p[1]) >>> 24) < 32) clear++;
        return clear >= 2;
    }

    static File dir(Context context) {
        File dir = new File(context.getCacheDir(), "media");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** The cached file for an attachment, or for its small preview when thumb is true. */
    static File file(Context context, JSONObject media, boolean thumb) {
        String id = thumb ? PairedProtocol.string(media, "thumbnailMediaID") : PairedProtocol.string(media, "mediaID");
        // Attachments without a download ID yet (preview only, or sent inline) are named by message and part.
        // ("preview2:" — earlier versions saved Google's in-message placeholder as if it were the photo.)
        if (id.isEmpty()) id = "preview2:" + media.optString("_message") + ":" + media.optString("_index") + (thumb ? ":thumb" : ":full");
        String ext = thumb ? "jpg" : extension(media);
        return new File(dir(context), hash(id) + (thumb ? "-thumb." : ".") + ext);
    }

    /**
     * A preview to show first: Google's thumbnail, or the small placeholder image it sometimes puts
     * inside the message itself. Neither is the photo; the full one is downloaded or asked for.
     */
    static boolean hasThumbnail(JSONObject media) { return !PairedProtocol.string(media, "thumbnailMediaID").isEmpty() || hasInline(media); }
    static boolean hasInline(JSONObject media) { return !PairedProtocol.string(media, "mediaData").isEmpty(); }
    /** The full attachment can be downloaded now. */
    static boolean hasFull(JSONObject media) {
        return !PairedProtocol.string(media, "mediaID").isEmpty() && !PairedProtocol.string(media, "decryptionKey").isEmpty();
    }
    /** A preview this small would turn into big blocks when stretched over a photo tile; it's shown blurred instead. */
    static boolean tiny(File file) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        return o.outWidth > 0 && Math.max(o.outWidth, o.outHeight) < 240;
    }
    private static final java.util.concurrent.ExecutorService inlineWriter = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final Handler mainThread = new Handler(Looper.getMainLooper());
    /** The full file isn't downloadable yet; the phone sends it when asked (with or without a preview). */
    static boolean needsPhone(JSONObject media) { return !hasFull(media); }
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
        String inline = thumb && id.isEmpty() ? PairedProtocol.string(media, "mediaData") : "";
        if (!inline.isEmpty()) {
            // The placeholder inside the message: saved like a thumbnail (no connection needed).
            inlineWriter.execute(() -> { String error = "";
                try { byte[] data = android.util.Base64.decode(inline, android.util.Base64.DEFAULT);
                    File part = new File(target.getPath() + ".part"); try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) { out.write(data); }
                    if (!part.renameTo(target)) error = "There is no room to save this preview."; }
                catch (Exception e) { error = "This preview couldn't be read."; }
                String err = error; mainThread.post(() -> { List<Done> list; synchronized (waiting) { list = waiting.remove(key); } if (list == null) return;
                    for (Done d : list) d.done(err.isEmpty() ? target : null, err); }); });
            return;
        }
        if (id.isEmpty() || secret.isEmpty()) {
            List<Done> list; synchronized (waiting) { list = waiting.remove(key); }
            if (list != null) for (Done d : list) d.done(null, thumb ? "No preview was sent with this photo." : "This photo hasn't been sent to linked devices yet. Your phone is being asked for it.");
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
        final int lenientSide = Math.max(boxWidth, boxHeight) * 2;
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
            } else if (HeifDecoder.preferred(file)) {
                bitmap = HeifDecoder.decode(file, lenientSide);
            }
            if (bitmap == null && !video) {
                bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                    int[] size = coverSize(info.getSize().getWidth(), info.getSize().getHeight(), boxWidth, boxHeight);
                    decoder.setTargetSize(size[0], size[1]);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
        } catch (Exception | OutOfMemoryError e) {
            bitmap = null;
        }
        // The stricter decoder refuses some slightly damaged or unusual JPEGs that the older one still shows.
        if (bitmap == null && !video) bitmap = decodeLeniently(file, lenientSide);
        // iPhone pictures Android can't read at all (Genmoji, stickers): decoded with the phone's video decoder.
        if (bitmap == null && !video && HeifDecoder.heif(file)) bitmap = HeifDecoder.decode(file, lenientSide);
        if (bitmap != null) { memory.put(key, bitmap); if (!video && sticker(bitmap)) stickers.add(file.getName()); }
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
        final int lenientSide = maxSide;
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
            } else if (HeifDecoder.preferred(file)) {
                bitmap = HeifDecoder.decode(file, maxSide);
            }
            if (bitmap == null && !video) {
                bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight(), longest = Math.max(w, h);
                    if (longest > maxSide) { float scale = maxSide / (float) longest; decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))); }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
        } catch (Exception | OutOfMemoryError e) {
            bitmap = null;
        }
        // The stricter decoder refuses some slightly damaged or unusual JPEGs that the older one still shows.
        if (bitmap == null && !video) bitmap = decodeLeniently(file, lenientSide);
        // iPhone pictures Android can't read at all (Genmoji, stickers): decoded with the phone's video decoder.
        if (bitmap == null && !video && HeifDecoder.heif(file)) bitmap = HeifDecoder.decode(file, lenientSide);
        if (bitmap != null) { memory.put(key, bitmap); if (!video && sticker(bitmap)) stickers.add(file.getName()); }
        return bitmap;
    }

    private static Bitmap decodeLeniently(File file, int maxSide) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1; while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options(); d.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(file.getPath(), d);
        } catch (Exception | OutOfMemoryError e) { return null; }
    }

    /** "2.1 MB, HEIC": what a downloaded file actually is, from its first bytes. */
    static String describe(File file) {
        byte[] b = new byte[16]; int n = 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) { n = Math.max(0, in.read(b)); } catch (Exception ignored) {}
        long size = file.length();
        return (size >= 104858 ? String.format(java.util.Locale.US, "%.1f MB", size / 1048576.0) : (size / 1024) + " KB") + ", " + kind(b, n);
    }

    static String kind(byte[] b, int n) {
        if (n <= 0) return "an empty file";
        String a = new String(b, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (n >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "JPEG";
        if (n >= 4 && (b[0] & 0xff) == 0x89 && a.startsWith("PNG", 1)) return "PNG";
        if (a.startsWith("GIF8")) return "GIF";
        if (a.startsWith("RIFF") && a.length() >= 12 && a.startsWith("WEBP", 8)) return "WebP";
        if (n >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0x0A || a.startsWith("JXL ", 4)) return "JPEG XL";
        if (a.length() >= 12 && a.startsWith("ftyp", 4)) {
            String brand = a.substring(8, 12).trim();
            if (brand.startsWith("hei") || brand.startsWith("hev") || brand.equals("mif1") || brand.equals("msf1")) return "HEIC";
            if (brand.startsWith("avi")) return "AVIF";
            return "video (" + brand + ")";
        }
        String t = a.trim();
        if (t.startsWith("<") || t.startsWith("{")) return "a web page or error, not a photo";
        return "unrecognized data";
    }

    /** Drops any decoded copies of a file that was deleted. */
    static void forget(File file) {
        String prefix = file.getName() + "@";
        for (String k : memory.snapshot().keySet()) if (k.startsWith(prefix)) memory.remove(k);
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
