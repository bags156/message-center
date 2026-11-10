package io.github.bags156.messagecenter;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoPrepTest {
    @Test public void fitKeepsShapeAndOnlyShrinks() {
        assertArrayEquals(new int[]{1600, 1200}, PhotoPrep.fit(4000, 3000, 1600));
        assertArrayEquals(new int[]{1200, 1600}, PhotoPrep.fit(3000, 4000, 1600));
        assertArrayEquals(new int[]{800, 600}, PhotoPrep.fit(800, 600, 1600));
        assertArrayEquals(new int[]{4096, 3072}, PhotoPrep.fit(8160, 6120, 4096));
    }
    @Test public void whatNeedsWork() {
        assertTrue("a 50 MP photo for MMS", PhotoPrep.needsWork("image/jpeg", 8160, 6120, 18_000_000, true));
        assertTrue("a 50 MP photo for RCS", PhotoPrep.needsWork("image/jpeg", 8160, 6120, 18_000_000, false));
        assertTrue("HEIC always", PhotoPrep.needsWork("image/heic", 1000, 800, 200_000, false));
        assertTrue("small pixels but over 1 MB for MMS", PhotoPrep.needsWork("image/png", 1080, 2400, 2_500_000, true));
        assertFalse("already small", PhotoPrep.needsWork("image/jpeg", 1200, 900, 300_000, true));
        assertFalse("fine for RCS", PhotoPrep.needsWork("image/jpeg", 2400, 1800, 1_500_000, false));
        assertTrue("12 MP is more than RCS needs", PhotoPrep.needsWork("image/jpeg", 4000, 3000, 3_000_000, false));
        assertFalse("GIFs keep moving", PhotoPrep.needsWork("image/gif", 4000, 3000, 9_000_000, true));
        assertFalse("not a photo", PhotoPrep.needsWork("video/mp4", 1920, 1080, 30_000_000, true));
    }
}
