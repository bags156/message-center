package io.github.bags156.messagecenter;

import java.util.Locale;

/**
 * The rules for "Text them when I get there" (see Arrivals, which watches the place). Plain
 * Java, so it's tested without a phone.
 * <ul>
 * <li>A text goes when you arrive (or, if you chose so, when you leave). If you're already
 * there when you set up an arrival text, it waits until you've left and come back, so it doesn't
 * fire on the spot; a leaving text set up somewhere else waits until you've got there.</li>
 * <li>"Just once" ends after sending (or after a day, if it never happens); "Every time" waits
 * for the opposite move before it can send again.</li>
 * <li>Check-in: "if I'm not there by 11 PM, text them…". At that time, unless you've arrived
 * (or are there), the late text goes; the arrival text still goes when you get there.</li>
 * </ul>
 */
final class ArrivalPlan {
    /** How close counts as arrived: a house and its driveway, a small office building. */
    static final float RADIUS_M = 150f;
    static final long ONCE_FOR_MS = 24 * 3_600_000L;
    /** ARMED: the next arrival (or departure) sends. AWAY_FIRST: it waits for the opposite move first. */
    static final String ARMED = "armed", AWAY_FIRST = "away_first";
    /** Text when you get there, or when you leave. */
    static final String ARRIVE = "arrive", LEAVE = "leave";

    /** The distance in meters between two points on the earth (haversine). */
    static double meters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000, dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Whether you're at the place (a little leeway for location drift); false if unknown (-1). */
    static boolean there(double metersAway, float radius) { return metersAway >= 0 && metersAway <= radius * 1.3; }

    /** Where an arrival text starts: waiting for you to leave if you're there already, else ready. */
    static String startState(double metersAway, float radius) { return startState(metersAway, radius, false); }
    /** For a leaving text: ready if you're there now (or it isn't known), else waiting for you to get there. */
    static String startState(double metersAway, float radius, boolean leave) {
        if (!leave) return there(metersAway, radius) ? AWAY_FIRST : ARMED;
        return metersAway < 0 || there(metersAway, radius) ? ARMED : AWAY_FIRST;
    }

    /** Whether this arrival or departure sends the text. */
    static boolean sends(String state, boolean entering) { return sends(state, entering, false); }
    static boolean sends(String state, boolean entering, boolean leave) { return entering != leave && ARMED.equals(state); }

    /** The state after a move that didn't send: the opposite move gets it ready again. */
    static String after(String state, boolean entering) { return after(state, entering, false); }
    static String after(String state, boolean entering, boolean leave) { return entering == leave ? ARMED : state; }

    /** The next time of day hour:minute from now (later today, or tomorrow). */
    static long deadline(long now, java.util.TimeZone zone, int hour, int minute) {
        java.util.Calendar at = java.util.Calendar.getInstance(zone);
        at.setTimeInMillis(now); at.set(java.util.Calendar.HOUR_OF_DAY, hour); at.set(java.util.Calendar.MINUTE, minute);
        at.set(java.util.Calendar.SECOND, 0); at.set(java.util.Calendar.MILLISECOND, 0);
        if (at.getTimeInMillis() <= now) at.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return at.getTimeInMillis();
    }

    /** At the check-in time: the late text goes unless you've arrived, are there, or it already went. */
    static boolean late(long deadline, long now, long arrivedAt, boolean lateSent, boolean thereNow) {
        return deadline > 0 && now >= deadline - 60_000 && arrivedAt <= 0 && !lateSent && !thereNow;
    }

    /** A check-in keeps watching for the arrival a while after its time (so "made it" still goes). */
    static long expires(long created, boolean everyTime, long deadline) {
        long base = expires(created, everyTime);
        return base == 0 || deadline <= 0 ? base : Math.max(base, deadline + 12 * 3_600_000L);
    }

    /** A one-time text gives up a day after it was set; one for every arrival lasts until you cancel it. */
    static long expires(long created, boolean everyTime) { return everyTime ? 0 : created + ONCE_FOR_MS; }
    static boolean expired(long expires, long now) { return expires > 0 && now >= expires; }

    /** Leaving: "Leaving home now.", "Leaving Work now." */
    static String defaultLeaveText(String place) {
        String p = place == null ? "" : place.trim();
        if (p.isEmpty()) return "Leaving now.";
        return p.toLowerCase(Locale.ROOT).equals("home") ? "Leaving home now." : "Leaving " + p + " now.";
    }

    /** Check-in: "Not home yet, running late." */
    static String defaultLateText(String place) {
        String p = place == null ? "" : place.trim();
        if (p.isEmpty()) return "Not there yet, running late.";
        return p.toLowerCase(Locale.ROOT).equals("home") ? "Not home yet, running late." : "Not at " + p + " yet, running late.";
    }

    /** What it says unless you change it: "Made it home!", "Made it to Work!" */
    static String defaultText(String place) {
        String p = place == null ? "" : place.trim();
        if (p.isEmpty()) return "Made it!";
        return p.toLowerCase(Locale.ROOT).equals("home") ? "Made it home!" : "Made it to " + p + "!";
    }

    private ArrivalPlan() {}
}
