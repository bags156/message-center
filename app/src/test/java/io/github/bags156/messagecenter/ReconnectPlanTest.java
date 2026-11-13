package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReconnectPlanTest {
    @Test public void quickFirstThenEveryFiveMinutes() {
        assertEquals(5_000, ReconnectPlan.wait(0, 0, 0));
        assertEquals(15_000, ReconnectPlan.wait(1, 0, 0));
        assertEquals(30_000, ReconnectPlan.wait(2, 0, 0));
        assertEquals(60_000, ReconnectPlan.wait(3, 0, 0));
        assertEquals(300_000, ReconnectPlan.wait(5, 0, 0));
        assertEquals(300_000, ReconnectPlan.wait(40, 0, 0));
    }

    @Test public void phoneNotAnsweringStartsGentler() {
        assertEquals(30_000, ReconnectPlan.wait(0, 2, 0));
        assertEquals(60_000, ReconnectPlan.wait(3, 2, 0));
    }

    @Test public void aLittleSpread() {
        assertEquals(6_000, ReconnectPlan.wait(0, 0, 1));
        assertEquals(5_500, ReconnectPlan.wait(0, 0, 0.5));
        assertEquals(6_000, ReconnectPlan.wait(0, 0, 7));
    }

    @Test public void shortDropsStayQuiet() {
        assertFalse(ReconnectPlan.brief(0, 1_000_000));
        assertTrue(ReconnectPlan.brief(1_000_000, 1_010_000));
        assertFalse(ReconnectPlan.brief(1_000_000, 1_020_000));
        assertEquals(10_000, ReconnectPlan.quietLeft(1_000_000, 1_010_000));
        assertEquals(0, ReconnectPlan.quietLeft(1_000_000, 1_030_000));
        assertTrue(ReconnectPlan.recovering("Connection ended • reconnecting on its own"));
        assertTrue(ReconnectPlan.recovering("Waiting for Google Messages on your phone • reconnecting on its own"));
        assertTrue(ReconnectPlan.recovering("Weak connection • reconnecting on its own"));
        assertTrue(ReconnectPlan.recovering("Connecting to Google Messages…"));
        assertFalse(ReconnectPlan.recovering("Google session expired. Sign in again."));
        assertFalse(ReconnectPlan.recovering(null));
    }
}
