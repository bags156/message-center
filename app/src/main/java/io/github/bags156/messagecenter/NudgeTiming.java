package io.github.bags156.messagecenter;

import java.util.Calendar;
import java.util.TimeZone;

/** When an unread pinned chat should nudge you next (pure logic, so it can be tested). */
final class NudgeTiming {
    private NudgeTiming() {}
    /** Nudges are only about recent messages; an old unread chat doesn't start nudging. */
    static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    /** At most this many nudges for one message. */
    static final int MAX_NUDGES = 3;
    static final int QUIET_START_HOUR = 22, QUIET_END_HOUR = 8;

    /**
     * The next nudge time for a message received at {@code received}, given the last nudge for it
     * ({@code lastNudge}, 0 if none) and how many were sent. Returns -1 when no more nudges are due.
     * Times that fall overnight (10 PM to 8 AM) move to 8 AM.
     */
    static long next(long received, long lastNudge, int sent, long delay, TimeZone zone) {
        if (received <= 0 || delay <= 0 || sent >= MAX_NUDGES) return -1;
        long due = Math.max(received, lastNudge) + delay;
        if (due - received > WINDOW_MS) return -1;
        return outsideQuietHours(due, zone);
    }

    static long outsideQuietHours(long time, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone); c.setTimeInMillis(time);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        if (hour >= QUIET_START_HOUR) c.add(Calendar.DAY_OF_YEAR, 1);
        else if (hour >= QUIET_END_HOUR) return time;
        c.set(Calendar.HOUR_OF_DAY, QUIET_END_HOUR); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
