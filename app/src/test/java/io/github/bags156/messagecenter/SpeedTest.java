package io.github.bags156.messagecenter;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class SpeedTest {
    @Test public void stepsUpButNeverToASpeedThatFailed() {
        assertEquals(3f, Speed.faster(2f, Float.MAX_VALUE), 0);
        assertEquals(4f, Speed.faster(3f, Float.MAX_VALUE), 0);
        assertEquals(4f, Speed.faster(4f, Float.MAX_VALUE), 0);
        assertEquals(2f, Speed.faster(2f, 3f), 0);
        assertEquals(1.5f, Speed.faster(1f, 2f), 0);
    }
    @Test public void thinMeansUnderAWordASecond() {
        assertTrue(Speed.thin(Collections.singletonList("just a few words here"), 12));
        assertFalse(Speed.thin(Collections.singletonList("one two three four five six seven eight nine ten eleven twelve"), 6));
        assertFalse(Speed.thin(Arrays.asList("so", "yeah"), 2));            // too short to judge
        assertTrue(Speed.thin(Collections.emptyList(), 4));
    }
}
