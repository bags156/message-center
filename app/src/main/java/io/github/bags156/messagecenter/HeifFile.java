package io.github.bags156.messagecenter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the layout of a HEIF/HEIC file: which pictures it holds, how each is coded, and how
 * they fit together (tiles, transparency, rotation, cropping). iPhones send Genmoji and
 * stickers this way, with several sizes of the same picture and a separate transparency
 * layer, which Android's own decoder refuses. HeifDecoder decodes what this finds.
 * Plain Java, no Android, so it is unit tested directly.
 */
final class HeifFile {
    static final class Item {
        final int id;
        String type = "";
        boolean hidden;
        int width, height;
        byte[] hvcc;
        String auxType = "";
        int rotation;               // degrees anticlockwise, from irot
        long[] clap;                // 8 values: width N/D, height N/D, horizontal offset N/D, vertical offset N/D
        int matrix = 6;             // colour matrix (6 = BT.601), from colr nclx
        boolean fullRange = true, colr;
        final List<long[]> extents = new ArrayList<>();
        int construction;
        long baseOffset;
        final List<Integer> tiles = new ArrayList<>();
        int alpha = -1;
        boolean premultiplied, auxiliary, thumbnail, tile;
        Item(int id) { this.id = id; }
        boolean picture() { return type.equals("hvc1") || type.equals("grid"); }
        boolean alphaLayer() { return auxType.equals("urn:mpeg:hevc:2015:auxid:1") || auxType.equals("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"); }
    }

    final Map<Integer, Item> items = new LinkedHashMap<>();
    int primary = -1;
    private final byte[] data;
    private int idatStart = -1, idatLength;

    private HeifFile(byte[] data) { this.data = data; }

    /** Null if this isn't a HEIF file or its layout can't be read. */
    static HeifFile parse(byte[] data) {
        try {
            HeifFile f = new HeifFile(data);
            int pos = 0; boolean heif = false, meta = false;
            while (pos + 8 <= data.length) {
                long[] box = f.box(pos, data.length); if (box == null) break;
                int start = (int) box[1], end = (int) box[2]; String type = f.fourcc(pos + 4);
                if (type.equals("ftyp")) heif = true;
                else if (type.equals("meta")) { f.meta(start + 4, end); meta = true; }
                pos = end;
            }
            if (!heif || !meta || f.items.isEmpty()) return null;
            f.link();
            return f;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The picture to show: the largest one (Genmoji carry several sizes), else the file's main picture. */
    Item best() {
        Item best = null;
        for (Item i : items.values()) {
            if (!i.picture() || i.auxiliary || i.thumbnail || i.tile || (i.hidden && i.id != primary) || i.width <= 0 || i.height <= 0) continue;
            if (i.type.equals("hvc1") && i.hvcc == null) continue;
            long area = (long) i.width * i.height, top = best == null ? -1 : (long) best.width * best.height;
            if (area > top || area == top && i.id == primary) best = i;
        }
        return best;
    }

    /** The coded bytes of an item, joined from its pieces. */
    byte[] itemData(Item item) {
        if (item.construction > 1 || item.construction == 1 && idatStart < 0) throw new IllegalStateException("unsupported item location");
        int base = item.construction == 1 ? idatStart : 0, limit = item.construction == 1 ? idatStart + idatLength : data.length;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (long[] e : item.extents) {
            long from = base + item.baseOffset + e[0], length = e[1] == 0 ? limit - from : e[1];
            if (from < base || length < 0 || from + length > limit) throw new IllegalStateException("extent outside file");
            out.write(data, (int) from, (int) length);
        }
        return out.toByteArray();
    }

    /** Rows, columns, and output size of a tiled picture. */
    int[] grid(Item item) {
        byte[] d = itemData(item);
        if (d.length < 8) throw new IllegalStateException("short grid");
        boolean wide = (d[1] & 1) != 0; int rows = (d[2] & 0xff) + 1, cols = (d[3] & 0xff) + 1;
        int w = wide ? (int) u32(d, 4) : u16(d, 4), h = wide ? (int) u32(d, 8) : u16(d, 6);
        return new int[]{rows, cols, w, h};
    }

    /** The cropped area {left, top, width, height} for a picture of this size, or null for none. */
    static int[] cropRect(long[] clap, int width, int height) {
        if (clap == null || clap[1] == 0 || clap[3] == 0 || clap[5] == 0 || clap[7] == 0) return null;
        double cw = clap[0] / (double) clap[1], ch = clap[2] / (double) clap[3];
        double cx = clap[4] / (double) clap[5] + (width - 1) / 2.0, cy = clap[6] / (double) clap[7] + (height - 1) / 2.0;
        int left = (int) Math.round(cx - (cw - 1) / 2.0), top = (int) Math.round(cy - (ch - 1) / 2.0);
        int w = (int) Math.round(cw), h = (int) Math.round(ch);
        left = Math.max(0, left); top = Math.max(0, top); w = Math.min(w, width - left); h = Math.min(h, height - top);
        if (w <= 0 || h <= 0 || w == width && h == height) return null;
        return new int[]{left, top, w, h};
    }

    /** HEVC setup data (VPS, SPS, PPS) with start codes, as Android's decoder expects. */
    static byte[] parameterSets(byte[] hvcc) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int arrays = hvcc[22] & 0xff, p = 23;
        for (int a = 0; a < arrays; a++) {
            int count = u16(hvcc, p + 1); p += 3;
            for (int n = 0; n < count; n++) {
                int len = u16(hvcc, p); p += 2;
                out.write(0); out.write(0); out.write(0); out.write(1); out.write(hvcc, p, len); p += len;
            }
        }
        return out.toByteArray();
    }

    static int lengthSize(byte[] hvcc) { return (hvcc[21] & 3) + 1; }
    /** 0 = monochrome, 1 = 4:2:0, 2 = 4:2:2, 3 = 4:4:4. */
    static int chromaFormat(byte[] hvcc) { return hvcc[16] & 3; }
    static int bitDepth(byte[] hvcc) { return (hvcc[17] & 7) + 8; }

    /** Turns length-prefixed picture data into start-code-separated data. */
    static byte[] annexB(byte[] sample, int lengthSize) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(sample.length + 16);
        int p = 0;
        while (p + lengthSize <= sample.length) {
            long len = 0; for (int i = 0; i < lengthSize; i++) len = len << 8 | (sample[p + i] & 0xff);
            p += lengthSize;
            if (len <= 0 || p + len > sample.length) break;
            out.write(0); out.write(0); out.write(0); out.write(1); out.write(sample, p, (int) len); p += (int) len;
        }
        return out.toByteArray();
    }

    // --- box reading ---

    /** {header size, payload start, end} of the box at pos. */
    private long[] box(int pos, int limit) {
        if (pos + 8 > limit) return null;
        long size = u32(data, pos); int header = 8;
        if (size == 1) { if (pos + 16 > limit) return null; size = u64(data, pos + 8); header = 16; }
        else if (size == 0) size = limit - pos;
        if (size < header || pos + size > limit) return null;
        return new long[]{header, pos + header, pos + size};
    }

    private void meta(int pos, int end) {
        List<int[]> properties = new ArrayList<>(); int ipma = -1, ipmaEnd = -1, iref = -1, irefEnd = -1;
        while (pos + 8 <= end) {
            long[] b = box(pos, end); if (b == null) break;
            int s = (int) b[1], e = (int) b[2]; String type = fourcc(pos + 4);
            switch (type) {
                case "pitm": primary = (data[s] & 0xff) == 0 ? u16(data, s + 4) : (int) u32(data, s + 4); break;
                case "iloc": iloc(s, e); break;
                case "iinf": iinf(s, e); break;
                case "iref": iref = s; irefEnd = e; break;
                case "idat": idatStart = s; idatLength = e - s; break;
                case "iprp": {
                    int p = s;
                    while (p + 8 <= e) {
                        long[] c = box(p, e); if (c == null) break;
                        String t = fourcc(p + 4);
                        if (t.equals("ipco")) { int q = (int) c[1]; while (q + 8 <= (int) c[2]) { long[] d = box(q, (int) c[2]); if (d == null) break; properties.add(new int[]{q, (int) d[1], (int) d[2]}); q = (int) d[2]; } }
                        else if (t.equals("ipma")) { ipma = (int) c[1]; ipmaEnd = (int) c[2]; }
                        p = (int) c[2];
                    }
                    break;
                }
                default: break;
            }
            pos = e;
        }
        if (ipma >= 0) ipma(ipma, ipmaEnd, properties);
        if (iref >= 0) iref(iref, irefEnd);
    }

    private Item item(int id) { Item i = items.get(id); if (i == null) { i = new Item(id); items.put(id, i); } return i; }

    private void iloc(int s, int e) {
        int version = data[s] & 0xff, p = s + 4;
        int offsetSize = (data[p] & 0xff) >> 4, lengthSize = data[p] & 0x0f, baseSize = (data[p + 1] & 0xff) >> 4, indexSize = version >= 1 ? data[p + 1] & 0x0f : 0;
        p += 2;
        long count; if (version < 2) { count = u16(data, p); p += 2; } else { count = u32(data, p); p += 4; }
        for (long n = 0; n < count && p < e; n++) {
            int id; if (version < 2) { id = u16(data, p); p += 2; } else { id = (int) u32(data, p); p += 4; }
            Item item = item(id);
            if (version >= 1) { item.construction = u16(data, p) & 0x0f; p += 2; }
            p += 2; // data reference index
            item.baseOffset = sized(p, baseSize); p += baseSize;
            int extents = u16(data, p); p += 2;
            for (int x = 0; x < extents; x++) {
                p += indexSize;
                long off = sized(p, offsetSize); p += offsetSize;
                long len = sized(p, lengthSize); p += lengthSize;
                item.extents.add(new long[]{off, len});
            }
        }
    }

    private void iinf(int s, int e) {
        int version = data[s] & 0xff, p = s + 4 + (version == 0 ? 2 : 4);
        while (p + 8 <= e) {
            long[] b = box(p, e); if (b == null) break;
            if (fourcc(p + 4).equals("infe")) {
                int q = (int) b[1], v = data[q] & 0xff; boolean hidden = (data[q + 3] & 1) != 0;
                if (v >= 2) {
                    int id; q += 4; if (v == 2) { id = u16(data, q); q += 2; } else { id = (int) u32(data, q); q += 4; }
                    q += 2; // protection index
                    Item item = item(id); item.type = fourcc(q); item.hidden = hidden;
                }
            }
            p = (int) b[2];
        }
    }

    private void ipma(int s, int e, List<int[]> properties) {
        int version = data[s] & 0xff; boolean wide = (data[s + 3] & 1) != 0; int p = s + 4;
        long count = u32(data, p); p += 4;
        for (long n = 0; n < count && p < e; n++) {
            int id; if (version < 1) { id = u16(data, p); p += 2; } else { id = (int) u32(data, p); p += 4; }
            int associations = data[p++] & 0xff; Item item = item(id);
            for (int a = 0; a < associations; a++) {
                int index; if (wide) { index = u16(data, p) & 0x7fff; p += 2; } else { index = data[p++] & 0x7f; }
                if (index > 0 && index <= properties.size()) property(item, properties.get(index - 1));
            }
        }
    }

    private void property(Item item, int[] box) {
        String type = fourcc(box[0] + 4); int s = box[1], e = box[2];
        switch (type) {
            case "hvcC": if (e - s >= 23) item.hvcc = java.util.Arrays.copyOfRange(data, s, e); break;
            case "ispe": item.width = (int) u32(data, s + 4); item.height = (int) u32(data, s + 8); break;
            case "auxC": { int q = s + 4, z = q; while (z < e && data[z] != 0) z++; item.auxType = new String(data, q, z - q, StandardCharsets.US_ASCII); break; }
            case "irot": item.rotation = (data[s] & 3) * 90; break;
            case "clap": { long[] c = new long[8]; for (int i = 0; i < 8; i++) { long v = u32(data, s + i * 4); c[i] = (i == 4 || i == 6) ? (int) v : v; } item.clap = c; break; }
            case "colr": if (fourcc(s).equals("nclx") && e - s >= 11) { item.matrix = u16(data, s + 8); item.fullRange = (data[s + 10] & 0x80) != 0; item.colr = true; } break;
            default: break;
        }
    }

    private void iref(int s, int e) {
        int version = data[s] & 0xff, p = s + 4;
        while (p + 8 <= e) {
            long[] b = box(p, e); if (b == null) break;
            String type = fourcc(p + 4); int q = (int) b[1];
            int from; if (version == 0) { from = u16(data, q); q += 2; } else { from = (int) u32(data, q); q += 4; }
            int count = u16(data, q); q += 2;
            List<Integer> to = new ArrayList<>();
            for (int n = 0; n < count; n++) { if (version == 0) { to.add(u16(data, q)); q += 2; } else { to.add((int) u32(data, q)); q += 4; } }
            Item source = item(from);
            switch (type) {
                case "dimg": source.tiles.clear(); source.tiles.addAll(to); for (int t : to) item(t).tile = true; break;
                case "auxl": source.auxiliary = true; for (int t : to) if (source.alphaLayer() || source.auxType.isEmpty()) item(t).alpha = from; break;
                case "thmb": source.thumbnail = true; break;
                case "prem": source.premultiplied = true; break;
                default: break;
            }
            p = (int) b[2];
        }
    }

    /** An auxiliary layer that turned out not to be transparency (a depth map, say) is not used as alpha. */
    private void link() {
        for (Item i : items.values()) if (i.alpha >= 0) { Item a = items.get(i.alpha); if (a == null || !a.auxType.isEmpty() && !a.alphaLayer()) i.alpha = -1; }
    }

    private String fourcc(int p) { return new String(data, p, 4, StandardCharsets.ISO_8859_1); }
    private long sized(int p, int size) { long v = 0; for (int i = 0; i < size; i++) v = v << 8 | (data[p + i] & 0xff); return v; }
    static int u16(byte[] d, int p) { return (d[p] & 0xff) << 8 | (d[p + 1] & 0xff); }
    static long u32(byte[] d, int p) { return (long) (d[p] & 0xff) << 24 | (d[p + 1] & 0xff) << 16 | (d[p + 2] & 0xff) << 8 | (d[p + 3] & 0xff); }
    private static long u64(byte[] d, int p) { return u32(d, p) << 32 | u32(d, p + 4); }
}
