package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.Test;

public class FontFileTest {
    /** A minimal font file: just a table directory and a character map. */
    private static byte[] font(Map<Integer, Integer> map) {
        byte[] cmap = FontFile.buildCmap(map);
        ByteBuffer b = ByteBuffer.allocate(12 + 16 + cmap.length);
        b.putInt(0x00010000).putShort((short) 1).putShort((short) 16).putShort((short) 0).putShort((short) 0);
        b.putInt(0x636D6170).putInt(0).putInt(28).putInt(cmap.length);
        b.put(cmap);
        return b.array();
    }

    private static Map<Integer, Integer> letters() {
        Map<Integer, Integer> m = new TreeMap<>();
        for (int c = 0x20; c < 0x7F; c++) m.put(c, c - 0x1D);
        m.put(0xA0, 3); // no-break space shares the space's glyph
        return m;
    }

    @Test public void readsWhatItWrites() throws Exception {
        Map<Integer, Integer> m = letters();
        m.put(0x1F600, 500);
        assertEquals(m, FontFile.cmap(font(m)));
    }

    @Test public void anOrdinaryFontIsLeftAlone() {
        byte[] f = font(letters());
        FontFile.Fixed fixed = FontFile.fix(f);
        assertSame(f, fixed.data);
        assertTrue(fixed.moved.isEmpty());
    }

    @Test public void aDemoFontsMarksGoToThePhonesFont() throws Exception {
        Map<Integer, Integer> m = letters();
        int demo = 900;
        for (int c : new int[]{'4', '9', '\'', '"', 0x22EE}) m.put(c, demo);
        for (int c = 0x2190; c < 0x2198; c++) m.put(c, demo);
        FontFile.Fixed fixed = FontFile.fix(font(m));
        Set<Integer> moved = fixed.moved;
        assertEquals(13, moved.size());
        Map<Integer, Integer> after = FontFile.cmap(fixed.data);
        assertFalse(after.containsKey((int) '4'));
        assertFalse(after.containsKey(0x22EE));
        assertEquals(m.get((int) 'A'), after.get((int) 'A'));
        assertEquals(3, (int) after.get(0xA0));
        assertEquals("4 9 \"", FontFile.examples(moved, 3));
    }

    @Test public void notAFontIsLeftAlone() {
        byte[] junk = "Not really a font at all".getBytes();
        assertSame(junk, FontFile.fix(junk).data);
    }
}
