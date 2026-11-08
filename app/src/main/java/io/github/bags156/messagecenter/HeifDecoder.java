package io.github.bags156.messagecenter;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes HEIC pictures that Android's own image decoder refuses or flattens, such as iPhone
 * Genmoji and stickers: several sizes of one picture plus a separate transparency layer. It
 * reads the file with HeifFile, picks the largest size, and runs each coded picture through
 * the phone's HEVC video decoder.
 */
final class HeifDecoder {
    private static final long MAX_FILE = 40L << 20, MAX_PIXELS = 24_000_000L, SMALL_FILE = 2L << 20;
    /** The last reason a decode failed, for diagnostics. */
    static volatile String lastProblem = "";

    private interface Sink { void frame(int index, Planes planes); }

    /** A decoded picture's samples, from the phone's decoder or the sandboxed software one. */
    private static final class Planes {
        byte[] y, u, v;
        int yRow, yPix, uRow, uPix, vRow, vPix, left, top, width, height, shiftX, shiftY, hi;
        static Planes of(Image image) {
            Planes p = new Planes(); Rect crop = image.getCropRect(); Image.Plane[] planes = image.getPlanes();
            p.hi = image.getFormat() == ImageFormat.YCBCR_P010 ? 1 : 0; // 10-bit: the high byte holds the top 8 bits
            p.y = bytes(planes[0].getBuffer()); p.yRow = planes[0].getRowStride(); p.yPix = planes[0].getPixelStride();
            if (planes.length >= 3) { p.u = bytes(planes[1].getBuffer()); p.v = bytes(planes[2].getBuffer()); p.uRow = planes[1].getRowStride(); p.uPix = planes[1].getPixelStride(); p.vRow = planes[2].getRowStride(); p.vPix = planes[2].getPixelStride(); }
            p.left = crop.left; p.top = crop.top; p.width = crop.width(); p.height = crop.height(); p.shiftX = 1; p.shiftY = 1;
            return p;
        }
        static Planes of(HevcSandbox.Picture pic) {
            Planes p = new Planes(); p.y = pic.planes[0]; p.yRow = pic.widths[0]; p.yPix = 1; p.width = pic.widths[0]; p.height = pic.heights[0];
            if (pic.chroma != 0) {
                p.u = pic.planes[1]; p.v = pic.planes[2]; p.uRow = pic.widths[1]; p.vRow = pic.widths[2]; p.uPix = 1; p.vPix = 1;
                p.shiftX = pic.chroma == 3 ? 0 : 1; p.shiftY = pic.chroma == 1 ? 1 : 0;
            }
            return p;
        }
    }

    /** True for small HEIC files (stickers, Genmoji) with transparency or several sizes, which Android's decoder would show wrongly. */
    static boolean preferred(File file) {
        if (file.length() > SMALL_FILE || !heif(file)) return false;
        try {
            HeifFile f = HeifFile.parse(java.nio.file.Files.readAllBytes(file.toPath()));
            HeifFile.Item best = f == null ? null : f.best();
            return best != null && (best.alpha >= 0 || best.id != f.primary);
        } catch (Exception | OutOfMemoryError e) { return false; }
    }

    /** True if the file starts like a HEIF/HEIC picture. */
    static boolean heif(File file) {
        byte[] b = new byte[12];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(b) < 12) return false;
        } catch (Exception e) { return false; }
        String box = new String(b, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1), brand = new String(b, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        return box.equals("ftyp") && (brand.startsWith("hei") || brand.startsWith("hev") || brand.equals("mif1") || brand.equals("msf1"));
    }

    /** The picture at most maxSide pixels on its longest edge, or null if this file can't be decoded this way. */
    static Bitmap decode(File file, int maxSide) {
        try {
            if (!file.isFile() || file.length() > MAX_FILE) return null;
            HeifFile heif = HeifFile.parse(java.nio.file.Files.readAllBytes(file.toPath()));
            HeifFile.Item item = heif == null ? null : heif.best();
            if (item == null) { lastProblem = "no picture in file"; return null; }
            int[] size = size(heif, item); int w = size[0], h = size[1];
            if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS) { lastProblem = "unsupported size " + w + "x" + h; return null; }
            int[] argb = new int[w * h];
            if (!render(heif, item, argb, w, h, false)) return null;
            if (item.alpha >= 0) {
                HeifFile.Item layer = heif.items.get(item.alpha); int[] as = size(heif, layer);
                // A transparency layer the phone can't decode leaves the picture opaque rather than missing.
                if (as[0] == w && as[1] == h) { int[] alpha = new int[w * h]; if (render(heif, layer, alpha, w, h, true)) applyAlpha(argb, alpha, item.premultiplied); }
            }
            Bitmap bitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
            int[] crop = HeifFile.cropRect(item.clap, w, h);
            if (crop != null) bitmap = Bitmap.createBitmap(bitmap, crop[0], crop[1], crop[2], crop[3]);
            if (item.rotation != 0) { Matrix m = new Matrix(); m.postRotate(-item.rotation); bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true); }
            int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (maxSide > 0 && longest > maxSide) {
                float s = maxSide / (float) longest;
                bitmap = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * s)), Math.max(1, Math.round(bitmap.getHeight() * s)), true);
            }
            return bitmap;
        } catch (Exception | OutOfMemoryError e) {
            lastProblem = e.toString();
            return null;
        }
    }

    private static int[] size(HeifFile heif, HeifFile.Item item) {
        if (item.type.equals("grid")) { int[] g = heif.grid(item); return new int[]{g[2], g[3]}; }
        return new int[]{item.width, item.height};
    }

    /** Decodes one picture (or every tile of a tiled one) into out, as colour or as transparency values. */
    private static boolean render(HeifFile heif, HeifFile.Item item, int[] out, int w, int h, boolean alphaOnly) {
        List<HeifFile.Item> parts = new ArrayList<>(); int cols = 1;
        if (item.type.equals("grid")) {
            int[] g = heif.grid(item); cols = g[1];
            if (item.tiles.size() != g[0] * g[1]) { lastProblem = "grid tiles missing"; return false; }
            for (int t : item.tiles) { HeifFile.Item tile = heif.items.get(t); if (tile == null || tile.hvcc == null || !tile.type.equals("hvc1")) { lastProblem = "unsupported tile"; return false; } parts.add(tile); }
        } else if (item.type.equals("hvc1") && item.hvcc != null) parts.add(item);
        else { lastProblem = "unsupported picture type " + item.type; return false; }
        HeifFile.Item first = parts.get(0); int tileW = first.width > 0 ? first.width : w, tileH = first.height > 0 ? first.height : h, columns = cols;
        // Tiles normally share one setup; if any differs, decode them one at a time.
        boolean shared = true; for (HeifFile.Item p : parts) shared &= java.util.Arrays.equals(p.hvcc, first.hvcc);
        // Colour details sit on a tiled picture itself, or failing that on its tiles.
        HeifFile.Item colour = item.colr || !item.type.equals("grid") ? item : first;
        int m = colour.matrix; boolean fr = colour.fullRange;
        if (shared) {
            List<byte[]> samples = new ArrayList<>(); for (HeifFile.Item p : parts) samples.add(heif.itemData(p));
            return decodeSamples(first.hvcc, samples, tileW, tileH, (index, planes) ->
                draw(planes, out, w, h, (index % columns) * tileW, (index / columns) * tileH, tileW, tileH, alphaOnly, m, fr));
        }
        for (int i = 0; i < parts.size(); i++) {
            HeifFile.Item p = parts.get(i); int x = (i % columns) * tileW, y = (i / columns) * tileH;
            List<byte[]> one = new ArrayList<>(); one.add(heif.itemData(p));
            if (!decodeSamples(p.hvcc, one, p.width, p.height, (index, planes) -> draw(planes, out, w, h, x, y, tileW, tileH, alphaOnly, m, fr))) return false;
        }
        return true;
    }

    /**
     * Runs coded pictures through the phone's HEVC decoders (software ones first, they handle
     * small sizes), or, for the 4:4:4 and monochrome pictures those refuse, through the
     * sandboxed software decoder.
     */
    private static boolean decodeSamples(byte[] hvcc, List<byte[]> samples, int width, int height, Sink sink) {
        byte[] csd = HeifFile.parameterSets(hvcc); int lengthSize = HeifFile.lengthSize(hvcc);
        List<byte[]> frames = new ArrayList<>(); int largest = 0;
        for (byte[] s : samples) { byte[] f = HeifFile.annexB(s, lengthSize); frames.add(f); largest = Math.max(largest, f.length); }
        boolean ordinary = HeifFile.chromaFormat(hvcc) == 1 && HeifFile.bitDepth(hvcc) <= 10;
        if (ordinary && phoneDecode(csd, frames, largest, width, height, sink)) return true;
        for (int i = 0; i < frames.size(); i++) {
            byte[] f = frames.get(i), stream = new byte[csd.length + f.length];
            System.arraycopy(csd, 0, stream, 0, csd.length); System.arraycopy(f, 0, stream, csd.length, f.length);
            HevcSandbox.Picture pic = HevcSandbox.decode(stream);
            if (pic == null) return false;
            sink.frame(i, Planes.of(pic));
        }
        return true;
    }

    private static boolean phoneDecode(byte[] csd, List<byte[]> frames, int largest, int width, int height, Sink sink) {
        for (String name : decoders()) {
            MediaCodec codec = null;
            try {
                codec = MediaCodec.createByCodecName(name);
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, largest + 4096);
                codec.configure(format, null, null, 0);
                codec.start();
                if (run(codec, frames, sink)) return true;
                lastProblem = name + ": no picture came out";
            } catch (Exception e) {
                lastProblem = name + ": " + e;
            } finally {
                if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            }
        }
        return false;
    }

    private static List<String> decoders() {
        List<String> software = new ArrayList<>(), hardware = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) { (info.isSoftwareOnly() ? software : hardware).add(info.getName()); break; }
        }
        software.addAll(hardware);
        return software;
    }

    private static boolean run(MediaCodec codec, List<byte[]> frames, Sink sink) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int fed = 0, got = 0; boolean ended = false;
        long deadline = System.nanoTime() + 6_000_000_000L + frames.size() * 1_000_000_000L;
        while (got < frames.size() && System.nanoTime() < deadline) {
            if (!ended) {
                int in = codec.dequeueInputBuffer(5_000);
                if (in >= 0) {
                    if (fed < frames.size()) {
                        ByteBuffer b = codec.getInputBuffer(in); byte[] f = frames.get(fed);
                        if (b == null || b.capacity() < f.length) return false;
                        b.clear(); b.put(f); codec.queueInputBuffer(in, 0, f.length, fed * 10_000L, 0); fed++;
                    } else { codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); ended = true; }
                }
            }
            int out = codec.dequeueOutputBuffer(info, 5_000);
            if (out >= 0) {
                boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0) {
                    Image image = codec.getOutputImage(out);
                    if (image != null) {
                        try {
                            int index = (int) Math.round(info.presentationTimeUs / 10_000.0);
                            sink.frame(index >= 0 && index < frames.size() ? index : got, Planes.of(image)); got++;
                        } finally { image.close(); }
                    }
                }
                codec.releaseOutputBuffer(out, false);
                if (end) break;
            }
        }
        return got >= frames.size();
    }

    /** Copies one decoded picture into the canvas at (x0, y0), converting YUV to colour (or taking brightness as transparency). */
    private static void draw(Planes p, int[] out, int outW, int outH, int x0, int y0, int w, int h, boolean alphaOnly, int matrix, boolean fullRange) {
        double kr, kb;
        switch (matrix) {
            case 1: kr = 0.2126; kb = 0.0722; break;
            case 9: case 10: kr = 0.2627; kb = 0.0593; break;
            case 4: kr = 0.30; kb = 0.11; break;
            case 7: kr = 0.212; kb = 0.087; break;
            default: kr = 0.299; kb = 0.114; break;
        }
        double kg = 1 - kr - kb, crR = 2 * (1 - kr), cbB = 2 * (1 - kb), crG = 2 * kr * (1 - kr) / kg, cbG = 2 * kb * (1 - kb) / kg;
        double ys = fullRange ? 1 : 255.0 / 219, cs = fullRange ? 1 : 255.0 / 224; int yo = fullRange ? 0 : 16;
        byte[] yb = p.y, ub = alphaOnly ? null : p.u, vb = alphaOnly ? null : p.v;
        int cw = Math.min(w, p.width), ch = Math.min(h, p.height);
        for (int y = 0; y < ch; y++) {
            int oy = y0 + y; if (oy >= outH) break;
            int sy = p.top + y;
            for (int x = 0; x < cw; x++) {
                int ox = x0 + x; if (ox >= outW) break;
                int sx = p.left + x, yi = sy * p.yRow + sx * p.yPix + p.hi;
                int yv = yi < yb.length ? yb[yi] & 0xff : 0;
                if (alphaOnly) { out[oy * outW + ox] = clamp((yv - yo) * ys); continue; }
                if (ub == null || vb == null) { int g = clamp((yv - yo) * ys); out[oy * outW + ox] = 0xff000000 | g << 16 | g << 8 | g; continue; }
                int cx = sx >> p.shiftX, cy = sy >> p.shiftY;
                int ui = cy * p.uRow + cx * p.uPix + p.hi, vi = cy * p.vRow + cx * p.vPix + p.hi;
                double l = (yv - yo) * ys, cb = ((ui < ub.length ? ub[ui] & 0xff : 128) - 128) * cs, cr = ((vi < vb.length ? vb[vi] & 0xff : 128) - 128) * cs;
                int r, g, b;
                if (matrix == 0) { g = clamp(yv); b = clamp(cb + 128); r = clamp(cr + 128); }
                else { r = clamp(l + crR * cr); g = clamp(l - cbG * cb - crG * cr); b = clamp(l + cbB * cb); }
                out[oy * outW + ox] = 0xff000000 | r << 16 | g << 8 | b;
            }
        }
    }

    private static void applyAlpha(int[] argb, int[] alpha, boolean premultiplied) {
        for (int i = 0; i < argb.length; i++) {
            int a = Math.max(0, Math.min(255, alpha[i])), c = argb[i];
            if (premultiplied && a > 0 && a < 255) {
                int r = Math.min(255, ((c >> 16) & 0xff) * 255 / a), g = Math.min(255, ((c >> 8) & 0xff) * 255 / a), b = Math.min(255, (c & 0xff) * 255 / a);
                c = r << 16 | g << 8 | b;
            }
            argb[i] = a << 24 | (c & 0xffffff);
        }
    }

    private static byte[] bytes(ByteBuffer buffer) { ByteBuffer b = buffer.duplicate(); b.rewind(); byte[] out = new byte[b.remaining()]; b.get(out); return out; }
    private static int clamp(double v) { return v <= 0 ? 0 : v >= 255 ? 255 : (int) (v + 0.5); }
}
