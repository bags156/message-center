package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.TimeZone;
import org.junit.Test;

/** Snoozing a chat privately (nothing comes through), say for a weekend with visitors. */
public class PrivateSnoozeTest {
    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Chicago");

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(ZONE); c.clear(); c.set(year, month, day, hour, minute, 0); return c.getTimeInMillis();
    }

    @Test public void privateSnoozeLetsNothingThroughUntilItEnds() {
        ChatSettings s = new ChatSettings(new MemoryPrefs());
        long now = System.currentTimeMillis();
        s.setSnooze("c1", now + 3_600_000L, true);
        assertTrue(s.snoozed("c1") && s.snoozedPrivately("c1"));
        s.setSnooze("c1", now + 3_600_000L);
        assertFalse("an ordinary snooze lets @-mentions through", s.snoozedPrivately("c1"));
        s.setSnooze("c1", now + 3_600_000L, true);
        s.snoozeUntilQuiet("c1");
        assertFalse(s.snoozedPrivately("c1"));
        s.setSnooze("c1", now - 1, true);
        assertFalse("over: notifications are back", s.snoozed("c1") || s.snoozedPrivately("c1"));
        s.setSnooze("c1", now + 3_600_000L, true);
        s.setSnooze("c1", 0);
        assertFalse(s.snoozedPrivately("c1"));
    }

    @Test public void theWeekendEndsMondayMorning() {
        assertEquals("Saturday → Monday 8:00", at(2026, Calendar.NOVEMBER, 23, 8, 0), ChatSettings.mondayMorning(at(2026, Calendar.NOVEMBER, 21, 10, 0), ZONE));
        assertEquals("late Sunday → the next morning", at(2026, Calendar.NOVEMBER, 23, 8, 0), ChatSettings.mondayMorning(at(2026, Calendar.NOVEMBER, 22, 23, 30), ZONE));
        assertEquals("early Monday → that morning", at(2026, Calendar.NOVEMBER, 23, 8, 0), ChatSettings.mondayMorning(at(2026, Calendar.NOVEMBER, 23, 6, 0), ZONE));
        assertEquals("Monday at 7:30 is too soon: next Monday", at(2026, Calendar.NOVEMBER, 30, 8, 0), ChatSettings.mondayMorning(at(2026, Calendar.NOVEMBER, 23, 7, 30), ZONE));
        assertEquals("Friday evening → Monday", at(2026, Calendar.NOVEMBER, 23, 8, 0), ChatSettings.mondayMorning(at(2026, Calendar.NOVEMBER, 20, 18, 0), ZONE));
    }
}
