package io.github.bags156.messagecenter;

/**
 * When to reconnect on its own after the connection to Google drops. Most drops are a network
 * switch or a moment without signal, so the first tries come quickly; then it eases off, but
 * never waits more than 5 minutes. A short drop isn't worth mentioning on screen.
 */
final class ReconnectPlan {
    /** 5 s, 15 s, 30 s, 1 min, 2 min, then every 5 min. */
    static final long[] STEPS = {5_000, 15_000, 30_000, 60_000, 120_000, 300_000};
    /** How long a drop may last before the app says it's reconnecting. */
    static final long QUIET_MS = 20_000;
    /** A connection that went quiet isn't reopened again sooner than this (if Google Messages on the phone is offline, reopening can't help). */
    static final long WATCHDOG_GAP_MS = 10 * 60_000;

    private ReconnectPlan() {}

    /**
     * The wait before try number tries (0 first), starting no earlier than step first (Google
     * Messages on the phone not answering starts gentler), plus up to a fifth more at random so
     * retries don't all land at once.
     */
    static long wait(int tries, int first, double random) {
        int step = Math.min(Math.max(tries, first), STEPS.length - 1);
        long base = STEPS[Math.max(0, step)];
        return base + (long) (base * 0.2 * Math.max(0, Math.min(1, random)));
    }

    /** Whether the connection has been down for so short a time (since downSince) that the app stays quiet about it. */
    static boolean brief(long downSince, long now) { return downSince > 0 && now - downSince < QUIET_MS; }

    /** How much longer the drop stays "brief" (0 once it isn't). */
    static long quietLeft(long downSince, long now) { return brief(downSince, now) ? QUIET_MS - (now - downSince) : 0; }

    /** The status lines that mean "on its way back", as opposed to something you need to do. */
    static boolean recovering(String status) {
        return status != null && (status.startsWith("Connection ended") || status.startsWith("Waiting for Google Messages") || status.startsWith("Weak connection")
            || status.startsWith("Connecting to Google Messages") || status.startsWith("Reconnecting"));
    }
}
