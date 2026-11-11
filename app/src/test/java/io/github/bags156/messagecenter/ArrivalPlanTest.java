package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArrivalPlanTest {
    @Test public void distancesAreRealisticMeters() {
        assertEquals(0, ArrivalPlan.meters(36.37, -94.21, 36.37, -94.21), 0.01);
        // One thousandth of a degree of latitude is about 111 m.
        assertEquals(111, ArrivalPlan.meters(36.370, -94.21, 36.371, -94.21), 1.5);
        // Two points 0.31° of latitude and 0.05° of longitude apart at 36°N: about 34.8 km in a straight line.
        assertEquals(34_800, ArrivalPlan.meters(36.3729, -94.2088, 36.0626, -94.1574), 300);
    }

    @Test public void alreadyThereWaitsForYouToLeaveFirst() {
        assertEquals(ArrivalPlan.AWAY_FIRST, ArrivalPlan.startState(0, ArrivalPlan.RADIUS_M));
        assertEquals(ArrivalPlan.AWAY_FIRST, ArrivalPlan.startState(180, ArrivalPlan.RADIUS_M));
        assertEquals(ArrivalPlan.ARMED, ArrivalPlan.startState(2_000, ArrivalPlan.RADIUS_M));
        assertEquals(ArrivalPlan.ARMED, ArrivalPlan.startState(-1, ArrivalPlan.RADIUS_M));
    }

    @Test public void onlyArrivingWhileReadySends() {
        assertTrue(ArrivalPlan.sends(ArrivalPlan.ARMED, true));
        assertFalse(ArrivalPlan.sends(ArrivalPlan.ARMED, false));
        assertFalse(ArrivalPlan.sends(ArrivalPlan.AWAY_FIRST, true));
        assertEquals(ArrivalPlan.ARMED, ArrivalPlan.after(ArrivalPlan.AWAY_FIRST, false));
        assertEquals(ArrivalPlan.AWAY_FIRST, ArrivalPlan.after(ArrivalPlan.AWAY_FIRST, true));
    }

    @Test public void justOnceLastsADayEveryTimeUntilCanceled() {
        long now = 1_800_000_000_000L;
        long once = ArrivalPlan.expires(now, false);
        assertEquals(now + ArrivalPlan.ONCE_FOR_MS, once);
        assertFalse(ArrivalPlan.expired(once, now + 1000)); assertTrue(ArrivalPlan.expired(once, once));
        assertEquals(0, ArrivalPlan.expires(now, true)); assertFalse(ArrivalPlan.expired(0, Long.MAX_VALUE));
    }

    @Test public void defaultMessagesReadNaturally() {
        assertEquals("Made it home!", ArrivalPlan.defaultText("Home"));
        assertEquals("Made it home!", ArrivalPlan.defaultText(" home "));
        assertEquals("Made it to Work!", ArrivalPlan.defaultText("Work"));
        assertEquals("Made it!", ArrivalPlan.defaultText(""));
    }
}
