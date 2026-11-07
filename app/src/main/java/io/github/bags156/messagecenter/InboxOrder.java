package io.github.bags156.messagecenter;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Predicate;

/** Inbox ordering and timestamp style, kept free of Android types for unit tests. */
final class InboxOrder {
    private InboxOrder() {}

    /** Pinned chats first, each group newest first. Does not modify the input. */
    static List<JSONObject> sort(List<JSONObject> chats, Predicate<JSONObject> pinned) {
        List<JSONObject> out = new ArrayList<>(chats);
        out.sort(Comparator.<JSONObject>comparingInt(c -> pinned.test(c) ? 0 : 1)
            .thenComparing(Comparator.comparingLong(PairedProtocol::time).reversed()));
        return out;
    }

    enum Stamp { TIME, WEEKDAY, MONTH_DAY, FULL_DATE }

    /** Today: time. Last 6 days: weekday. This year: month and day. Older: full date. */
    static Stamp stamp(long time, long now, TimeZone zone) {
        Calendar t = Calendar.getInstance(zone), n = Calendar.getInstance(zone);
        t.setTimeInMillis(time); n.setTimeInMillis(now);
        if (sameDay(t, n)) return Stamp.TIME;
        Calendar weekAgo = (Calendar) n.clone();
        weekAgo.add(Calendar.DAY_OF_YEAR, -6);
        weekAgo.set(Calendar.HOUR_OF_DAY, 0); weekAgo.set(Calendar.MINUTE, 0); weekAgo.set(Calendar.SECOND, 0); weekAgo.set(Calendar.MILLISECOND, 0);
        if (time >= weekAgo.getTimeInMillis() && time <= now) return Stamp.WEEKDAY;
        if (t.get(Calendar.YEAR) == n.get(Calendar.YEAR)) return Stamp.MONTH_DAY;
        return Stamp.FULL_DATE;
    }

    /** A centered time label goes above a message after an hour's gap or a new day. */
    static boolean separator(long previous, long current, TimeZone zone) {
        if (previous <= 0) return true;
        Calendar a = Calendar.getInstance(zone), b = Calendar.getInstance(zone);
        a.setTimeInMillis(previous); b.setTimeInMillis(current);
        return !sameDay(a, b) || current - previous >= 60 * 60 * 1000L;
    }

    /** Consecutive bubbles from the same side within a few minutes sit closer together. */
    static boolean grouped(boolean previousOutgoing, String previousSender, long previousTime,
                           boolean outgoing, String sender, long time) {
        return previousTime > 0 && previousOutgoing == outgoing && previousSender.equals(sender)
            && time - previousTime < 3 * 60 * 1000L;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
