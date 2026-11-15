package io.github.bags156.messagecenter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads and adjusts a TrueType/OpenType font file's character map. Demo fonts map every character
 * they leave out (often digits like 4, the apostrophe…) to one "DEMO" mark, so the phone never fills
 * those in from its own font. This finds such stand-in marks and writes a copy of the font that
 * simply doesn't list those characters, so Android draws them in the phone's font instead.
 * Pure Java, so tests run anywhere.
 */
final class FontFile {
    private FontFile() {}

    /** A glyph standing in for this many different characters (besides spaces) is a placeholder, not a letter. */
    static final int PLACEHOLDER_SHARE = 10;

    /** What fixing a font did: the new bytes (or the same, if nothing needed changing) and the characters given to the phone's font. */
    static final class Fixed {
        final byte[] data; final Set<Integer> moved;
        Fixed(byte[] data, Set<Integer> moved) { this.data = data; this.moved = moved; }
    }

    /** The font with its stand-in marks taken out of the character map; unchanged when there are none or it can't be read. */
    static Fixed fix(byte[] font) {
        try {
            Map<Integer, Integer> map = cmap(font);
            Set<Integer> drop = placeholders(map);
            if (drop.isEmpty()) return new Fixed(font, drop);
            Map<Integer, Integer> kept = new TreeMap<>(map);
            for (int c : drop) kept.remove(c);
            return new Fixed(withCmap(font, buildCmap(kept)), drop);
        } catch (Exception e) {
            return new Fixed(font, new TreeSet<>());
        }
    }

    /** Characters mapped to a glyph that stands in for many different characters (a demo font's "DEMO" mark, a box). */
    static Set<Integer> placeholders(Map<Integer, Integer> map) {
        Map<Integer, Integer> uses = new HashMap<>();
        for (int g : map.values()) uses.merge(g, 1, Integer::sum);
        Set<Integer> spaces = new TreeSet<>();
        for (int c : new int[]{0x20, 0xA0, 0x2007, 0x202F, 0x3000}) if (map.containsKey(c)) spaces.add(map.get(c));
        Set<Integer> out = new TreeSet<>();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            int g = e.getValue();
            if (g != 0 && !spaces.contains(g) && uses.get(g) >= PLACEHOLDER_SHARE) out.add(e.getKey());
        }
        return out;
    }

    // ---- Reading ----

    private static int u16(ByteBuffer b, int at) { return b.getShort(at) & 0xFFFF; }
    private static long u32(ByteBuffer b, int at) { return b.getInt(at) & 0xFFFFFFFFL; }

    /** Where the 'cmap' table's record is in the table directory, or -1. Collections (.ttc) aren't handled. */
    private static int cmapRecord(ByteBuffer b) throws IOException {
        int version = b.getInt(0);
        if (version != 0x00010000 && version != 0x4F54544F /*OTTO*/ && version != 0x74727565 /*true*/) throw new IOException("not a single font");
        int tables = u16(b, 4);
        for (int i = 0; i < tables; i++) {
            int rec = 12 + 16 * i;
            if (b.getInt(rec) == 0x636D6170 /*cmap*/) return rec;
        }
        throw new IOException("no cmap");
    }

    /** Character → glyph, from the font's best Unicode character map. */
    static Map<Integer, Integer> cmap(byte[] font) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(font);
        int rec = cmapRecord(b);
        int cmap = (int) u32(b, rec + 8);
        int count = u16(b, cmap + 2);
        int best = -1, bestRank = 0;
        for (int i = 0; i < count; i++) {
            int r = cmap + 4 + 8 * i, platform = u16(b, r), encoding = u16(b, r + 2);
            int sub = cmap + (int) u32(b, r + 4), format = u16(b, sub);
            int rank = format == 12 && ((platform == 3 && encoding == 10) || platform == 0) ? 3
                : format == 4 && ((platform == 3 && encoding == 1) || platform == 0) ? 2 : 0;
            if (rank > bestRank) { bestRank = rank; best = sub; }
        }
        if (best < 0) throw new IOException("no Unicode character map");
        Map<Integer, Integer> map = new TreeMap<>();
        if (u16(b, best) == 12) {
            long groups = u32(b, best + 12);
            if (groups > 200_000) throw new IOException("too many groups");
            for (int i = 0; i < groups; i++) {
                int g = best + 16 + 12 * i;
                long start = u32(b, g), end = u32(b, g + 4), glyph = u32(b, g + 8);
                if (end < start || end > 0x10FFFF || end - start > 0x10000) throw new IOException("bad group");
                for (long c = start; c <= end; c++) { int id = (int) (glyph + c - start); if (id != 0) map.put((int) c, id); }
            }
        } else {
            int segs = u16(b, best + 6) / 2;
            int ends = best + 14, starts = ends + 2 * segs + 2, deltas = starts + 2 * segs, offsets = deltas + 2 * segs;
            for (int i = 0; i < segs; i++) {
                int end = u16(b, ends + 2 * i), start = u16(b, starts + 2 * i), delta = b.getShort(deltas + 2 * i), offset = u16(b, offsets + 2 * i);
                for (int c = start; c <= end && c != 0xFFFF; c++) {
                    int id;
                    if (offset == 0) id = (c + delta) & 0xFFFF;
                    else {
                        int at = offsets + 2 * i + offset + 2 * (c - start);
                        id = at + 1 < font.length ? u16(b, at) : 0;
                        if (id != 0) id = (id + delta) & 0xFFFF;
                    }
                    if (id != 0) map.put(c, id);
                }
            }
        }
        return map;
    }

    // ---- Writing ----

    /** A character map table holding one format 12 subtable (every character), listed for Windows Unicode and Unicode full. */
    static byte[] buildCmap(Map<Integer, Integer> map) {
        List<long[]> groups = new ArrayList<>();
        long[] open = null;
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(map).entrySet()) {
            int c = e.getKey(), g = e.getValue();
            if (open != null && c == open[1] + 1 && g == open[2] + (c - open[0])) open[1] = c;
            else { open = new long[]{c, c, g}; groups.add(open); }
        }
        int subLength = 16 + 12 * groups.size();
        ByteBuffer t = ByteBuffer.allocate(4 + 8 * 2 + subLength);
        t.putShort((short) 0).putShort((short) 2);
        t.putShort((short) 0).putShort((short) 4).putInt(20);   // Unicode, full repertoire
        t.putShort((short) 3).putShort((short) 10).putInt(20);  // Windows, Unicode full repertoire
        t.putShort((short) 12).putShort((short) 0).putInt(subLength).putInt(0).putInt(groups.size());
        for (long[] g : groups) t.putInt((int) g[0]).putInt((int) g[1]).putInt((int) g[2]);
        return t.array();
    }

    /** The font with its 'cmap' table replaced (the new one goes at the end; the directory points to it). */
    static byte[] withCmap(byte[] font, byte[] cmap) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(font);
        int rec = cmapRecord(b);
        int at = (font.length + 3) & ~3;
        ByteArrayOutputStream out = new ByteArrayOutputStream(at + cmap.length + 4);
        out.write(font, 0, font.length);
        for (int i = font.length; i < at; i++) out.write(0);
        out.write(cmap, 0, cmap.length);
        for (int i = cmap.length; (i & 3) != 0; i++) out.write(0);
        byte[] result = out.toByteArray();
        ByteBuffer r = ByteBuffer.wrap(result);
        r.putInt(rec + 4, checksum(cmap)).putInt(rec + 8, at).putInt(rec + 12, cmap.length);
        return result;
    }

    private static int checksum(byte[] table) {
        long sum = 0;
        for (int i = 0; i < table.length; i += 4) {
            long word = 0;
            for (int k = 0; k < 4; k++) word = (word << 8) | (i + k < table.length ? table[i + k] & 0xFF : 0);
            sum += word;
        }
        return (int) sum;
    }

    /** A few of the moved characters to name ("4 9 '"): digits and letters first, then everyday punctuation, then the rest. */
    static String examples(Set<Integer> moved, int max) {
        List<String> out = new ArrayList<>();
        for (int pass = 0; pass < 3 && out.size() < max; pass++)
            for (int c : moved) {
                if (out.size() >= max) break;
                int kind = Character.isLetterOrDigit(c) && c < 0x250 ? 0 : "'\"!?&@#$%,.:;()-/".indexOf(c) >= 0 ? 1 : 2;
                if (kind == pass && c > 0x20 && !Character.isISOControl(c)) out.add(new String(Character.toChars(c)));
            }
        return String.join(" ", out);
    }
}
