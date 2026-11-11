package io.github.bags156.messagecenter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the time goes when a screen is drawn, for the device check that times opening and
 * leaving a chat. Off in normal use: each span costs one volatile read.
 */
final class Perf {
    private Perf() {}

    static volatile boolean on;
    private static final Map<String, long[]> spans = new LinkedHashMap<>();

    /** A start time to pass to {@link #end}, or 0 when timing is off. */
    static long start() { return on ? System.nanoTime() : 0; }

    static void end(String what, long started) {
        if (started == 0 || !on) return;
        long took = System.nanoTime() - started;
        synchronized (spans) { long[] s = spans.computeIfAbsent(what, k -> new long[2]); s[0] += took; s[1]++; }
    }

    static void clear() { synchronized (spans) { spans.clear(); } }

    /** "name 12.3 ms (×4)" per span, in the order they were first seen, divided by runs. */
    static String report(int runs) {
        StringBuilder out = new StringBuilder();
        synchronized (spans) {
            for (Map.Entry<String, long[]> e : spans.entrySet()) {
                if (out.length() > 0) out.append("; ");
                out.append(e.getKey()).append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", e.getValue()[0] / 1e6 / Math.max(1, runs))).append(" ms");
                if (e.getValue()[1] > runs) out.append(" (×").append(e.getValue()[1] / Math.max(1, runs)).append(')');
            }
        }
        return out.toString();
    }
}
