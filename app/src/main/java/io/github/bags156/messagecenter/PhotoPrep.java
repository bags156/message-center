package io.github.bags156.messagecenter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Gets a photo ready to send. A phone camera's photo can be 10-20 MB; a picture message (MMS)
 * carries about 1 MB, and Google Messages marks a bigger one "failed" every time it's retried. And
 * a photo sent from here crosses the network three times (up to Google, down to your phone, out to
 * them), so size is time. Photos are resized and re-encoded as JPEG when they need it: for MMS
 * chats to fit 1 MB, for RCS to 2560 px (about 1-2 MB). HEIC (which Google's upload doesn't name) becomes JPEG too. Turning it into
 * a new JPEG also leaves the photo's location and camera details behind. GIFs, other files and
 * photos already small enough are sent as they are.
 */
final class PhotoPrep {
    // RCS: sharp on any phone screen and quick to upload (about 1-2 MB); MMS: what a picture message carries.
    static final int RCS_EDGE = 2560, MMS_EDGE = 1600;
    static final long RCS_BYTES = 2_500_000, MMS_BYTES = 950_000;

    static final class Ready {
        final File file; final String name, mime;
        Ready(File file, String name, String mime) { this.file = file; this.name = name; this.mime = mime; }
    }

    /** The size to scale (w × h) to so its long edge is at most edge; the same size if it already fits. */
    static int[] fit(int w, int h, int edge) {
        int longest = Math.max(w, h);
        if (longest <= edge || longest <= 0) return new int[]{w, h};
        double s = edge / (double) longest;
        return new int[]{Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s))};
    }

    /** Whether a photo needs work before sending, from its type, pixel size and file size. */
    static boolean needsWork(String mime, int w, int h, long bytes, boolean mms) {
        String m = mime.toLowerCase(Locale.ROOT);
        if (!m.startsWith("image/") || m.equals("image/gif")) return false;
        boolean plain = m.equals("image/jpeg") || m.equals("image/jpg") || m.equals("image/png");
        return !plain || Math.max(w, h) > (mms ? MMS_EDGE : RCS_EDGE) || bytes > (mms ? MMS_BYTES : RCS_BYTES);
    }

    /** The photo to send (a new file next to the original if it was changed). Call off the main thread. */
    static Ready prepare(File in, String name, String mime, boolean mms) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (!m.startsWith("image/") || m.equals("image/gif")) return new Ready(in, name, mime);
        try {
            int[] size = new int[2];
            ImageDecoder.Source source = ImageDecoder.createSource(in);
            ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                size[0] = info.getSize().getWidth(); size[1] = info.getSize().getHeight();
                decoder.setTargetSize(1, 1); // only the size is wanted here
            });
            if (!needsWork(m, size[0], size[1], in.length(), mms)) return new Ready(in, name, mime);
            int edge = mms ? MMS_EDGE : RCS_EDGE;
            String base = name.replaceAll("\\.[A-Za-z0-9]+$", "");
            File out = new File(in.getParentFile(), in.getName().replaceAll("\\.[A-Za-z0-9]+$", "") + "-send.jpg");
            for (int attempt = 0; attempt < 4; attempt++) {
                int[] target = fit(size[0], size[1], edge);
                Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(in), (decoder, info, src) -> {
                    decoder.setTargetSize(target[0], target[1]);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                // JPEG has no transparency: put a see-through image (a sticker, a screenshot) on white rather than black.
                if (bitmap.hasAlpha()) {
                    Bitmap flat = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(flat); c.drawColor(Color.WHITE); c.drawBitmap(bitmap, 0, 0, null);
                    bitmap.recycle(); bitmap = flat;
                }
                for (int quality = 88; quality >= (mms ? 50 : 80); quality -= 8) {
                    try (FileOutputStream os = new FileOutputStream(out)) { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os); }
                    if (!mms || out.length() <= MMS_BYTES) { bitmap.recycle(); return new Ready(out, base + ".jpg", "image/jpeg"); }
                }
                bitmap.recycle();
                edge = Math.round(edge * 0.75f); // still too big for MMS: smaller again
            }
            return new Ready(out, base + ".jpg", "image/jpeg");
        } catch (Exception | OutOfMemoryError e) {
            Diagnostics.note("Photo couldn't be prepared for sending: " + e.getClass().getSimpleName());
            return new Ready(in, name, mime);
        }
    }

    private PhotoPrep() {}
}
