package io.github.bags156.messagecenter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.InputStream;
import org.junit.Test;

public class HeifFileTest {
    private static HeifFile load(String name) throws Exception {
        try (InputStream in = HeifFileTest.class.getResourceAsStream("/heif/" + name + ".heic")) { return HeifFile.parse(in.readAllBytes()); }
    }

    @Test public void genmojiPicksLargestSizeAndItsTransparency() throws Exception {
        HeifFile f = load("genmoji");
        HeifFile.Item best = f.best();
        assertEquals(1, f.primary);
        assertEquals(320, best.width);
        assertEquals(320, best.height);
        assertTrue(best.alpha >= 0);
        assertTrue(f.items.get(best.alpha).alphaLayer());
        byte[] stream = HeifFile.annexB(f.itemData(best), HeifFile.lengthSize(best.hvcc));
        assertEquals(1, stream[3]);
        byte[] setup = HeifFile.parameterSets(best.hvcc);
        assertEquals(32, (setup[4] >> 1) & 0x3f); // video parameter set first
    }

    @Test public void tiledPictureReadsLayoutFromItemData() throws Exception {
        HeifFile f = load("grid");
        HeifFile.Item best = f.best();
        assertEquals("grid", best.type);
        assertArrayEquals(new int[]{2, 2, 400, 300}, f.grid(best));
        assertEquals(4, best.tiles.size());
        for (int t : best.tiles) assertTrue(f.items.get(t).tile);
    }

    @Test public void cropAndRotation() throws Exception {
        HeifFile.Item best = load("turned").best();
        assertEquals(90, best.rotation);
        assertArrayEquals(new int[]{10, 10, 180, 100}, HeifFile.cropRect(best.clap, best.width, best.height));
    }

    @Test public void monochromeTransparencyIsLinked() throws Exception {
        HeifFile f = load("mono-alpha");
        assertTrue(f.best().alpha >= 0);
    }

    @Test public void notHeif() {
        assertNull(HeifFile.parse("<html>error</html>".getBytes()));
        assertNull(HeifFile.parse(new byte[0]));
        byte[] junk = new byte[64]; junk[3] = 64; junk[4] = 'f'; junk[5] = 't'; junk[6] = 'y'; junk[7] = 'p';
        assertNull(HeifFile.parse(junk));
    }

    @Test public void truncatedFileDoesNotCrash() throws Exception {
        byte[] whole;
        try (InputStream in = HeifFileTest.class.getResourceAsStream("/heif/genmoji.heic")) { whole = in.readAllBytes(); }
        for (int cut = 0; cut < whole.length; cut += 97) HeifFile.parse(java.util.Arrays.copyOf(whole, cut));
        assertNotNull(HeifFile.parse(whole));
    }
}
