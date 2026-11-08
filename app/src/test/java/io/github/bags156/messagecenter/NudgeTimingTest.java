package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import java.util.Calendar;
import java.util.TimeZone;
import org.junit.Test;

public class NudgeTimingTest {
    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Chicago");
    private static final long HOUR = 3_600_000L;
    private static long at(int day, int hour, int minute) { Calendar c = Calendar.getInstance(ZONE); c.clear(); c.set(2026, Calendar.NOVEMBER, day, hour, minute, 0); return c.getTimeInMillis(); }

    @Test public void firstNudgeComesAfterTheDelay() {
        assertEquals(at(7, 14, 0), NudgeTiming.next(at(7, 12, 0), 0, 0, 2 * HOUR, ZONE));
    }
    @Test public void laterNudgesCountFromTheLastOne() {
        assertEquals(at(7, 17, 30), NudgeTiming.next(at(7, 12, 0), at(7, 15, 30), 1, 2 * HOUR, ZONE));
    }
    @Test public void overnightNudgesWaitUntilMorning() {
        assertEquals(at(8, 8, 0), NudgeTiming.next(at(7, 21, 0), 0, 0, 2 * HOUR, ZONE));
        assertEquals(at(8, 8, 0), NudgeTiming.next(at(8, 3, 0), 0, 0, 2 * HOUR, ZONE));
        assertEquals(at(8, 8, 0), NudgeTiming.next(at(8, 6, 0), 0, 0, 1 * HOUR, ZONE));
    }
    @Test public void stopsAfterThreeOrADay() {
        assertEquals(-1, NudgeTiming.next(at(7, 12, 0), at(7, 18, 0), 3, 2 * HOUR, ZONE));
        assertEquals(-1, NudgeTiming.next(at(6, 9, 0), at(7, 8, 0), 2, 4 * HOUR, ZONE));
        assertEquals(-1, NudgeTiming.next(at(7, 12, 0), 0, 0, 0, ZONE));
    }
}
