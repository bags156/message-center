package io.github.bags156.messagecenter;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoQualityTest {
    @Test public void fastPreparesAnOrdinaryLargePhotoWhileFullKeepsASupportedOriginal(){
        assertTrue(PhotoPrep.needsWork("image/jpeg",4000,3000,4_000_000,false,PhotoQuality.FAST));
        assertTrue(PhotoPrep.needsWork("image/jpeg",4000,3000,4_000_000,false,PhotoQuality.STANDARD));
        assertFalse(PhotoPrep.needsWork("image/jpeg",4000,3000,4_000_000,false,PhotoQuality.FULL));
        assertTrue(PhotoPrep.needsWork("image/heic",4000,3000,4_000_000,false,PhotoQuality.FULL));
    }
    @Test public void mmsLimitsApplyEvenWhenFullQualityIsSelected(){
        assertEquals(PhotoQuality.bytes(PhotoQuality.FAST,true),PhotoQuality.bytes(PhotoQuality.FULL,true));
        assertTrue(PhotoPrep.needsWork("image/jpeg",1200,900,2_000_000,true,PhotoQuality.FULL));
        assertEquals(PhotoQuality.STANDARD,PhotoQuality.normalize("unknown"));
    }
}
