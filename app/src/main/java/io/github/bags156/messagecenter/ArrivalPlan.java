package io.github.bags156.messagecenter;

import java.util.Locale;

/**
 * The rules for "Text them when I get there" (see Arrivals, which watches the place). Plain
 * Java, so it's tested without a phone.
 * <ul>
 * <li>A text goes when you arrive. If you're already there when you set it up, it waits until
 * you've left and come back, so it doesn't fire on the spot.</li>
 * <li>"Just once" ends after sending (or after a day, if you never arrive); "Every time" waits
 * for you to leave again before it can send again.</li>
 * </ul>
 */
final class ArrivalPlan {
    /** How close counts as arrived: a house and its driveway, a small office building. */
    static final float RADIUS_M = 150f;
    static final long ONCE_FOR_MS = 24 * 3_600_000L;
    /** ARMED: the next arrival sends. AWAY_FIRST: you're there now (or just arrived); it waits for you to leave. */
    static final String ARMED = "armed", AWAY_FIRST = "away_first";

    /** The distance in meters between two points on the earth (haversine). */
    static double meters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000, dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Where it starts: waiting for you to leave if you're there already (a little leeway for location drift), else ready. */
    static String startState(double metersAway, float radius) {
        return metersAway >= 0 && metersAway <= radius * 1.3 ? AWAY_FIRST : ARMED;
    }

    /** Whether this arrival or departure sends the text. */
    static boolean sends(String state, boolean entering) { return entering && ARMED.equals(state); }

    /** The state after an arrival or departure that didn't send (leaving gets it ready again). */
    static String after(String state, boolean entering) { return entering ? state : ARMED; }

    /** A one-time text gives up a day after it was set; one for every arrival lasts until you cancel it. */
    static long expires(long created, boolean everyTime) { return everyTime ? 0 : created + ONCE_FOR_MS; }
    static boolean expired(long expires, long now) { return expires > 0 && now >= expires; }

    /** What it says unless you change it: "Made it home!", "Made it to Work!" */
    static String defaultText(String place) {
        String p = place == null ? "" : place.trim();
        if (p.isEmpty()) return "Made it!";
        return p.toLowerCase(Locale.ROOT).equals("home") ? "Made it home!" : "Made it to " + p + "!";
    }

    private ArrivalPlan() {}
}
