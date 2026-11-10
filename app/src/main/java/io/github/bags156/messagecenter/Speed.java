package io.github.bags156.messagecenter;

import android.content.SharedPreferences;

import java.util.List;

/**
 * How fast voice messages are played to this phone's speech recognizer. Recognizers differ and
 * don't say how fast they can listen, so this starts at 2× real time, goes a step faster each time
 * a whole message goes through, and a step slower (for good) when a piece comes back with too few
 * words. The steps are 1×, 1.5×, 2×, 3×, 4×.
 */
final class Speed {
    static final float[] STEPS = {1f, 1.5f, 2f, 3f, 4f};
    private static final String NOW = "speed", TOO_FAST = "speed_too_fast";

    /** The speed to use now. */
    static float now(SharedPreferences p) { return p.getFloat(NOW, 2f); }

    /** After a piece came back thin at this speed: the next step down, remembered (and this speed never tried again). */
    static float slower(SharedPreferences p, float speed) {
        float down = 1f;
        for (float s : STEPS) if (s < speed) down = s;
        p.edit().putFloat(NOW, down).putFloat(TOO_FAST, Math.min(p.getFloat(TOO_FAST, Float.MAX_VALUE), speed)).apply();
        return down;
    }

    /** After a whole message went through at this speed: the next step up for next time, unless that one has failed before. */
    static void worked(SharedPreferences p, float speed) {
        p.edit().putFloat(NOW, faster(speed, p.getFloat(TOO_FAST, Float.MAX_VALUE))).apply();
    }

    static float faster(float speed, float tooFast) {
        for (float s : STEPS) if (s > speed) return s < tooFast ? s : speed;
        return speed;
    }

    /**
     * Whether a piece of speech came back with far fewer words than it should have. People say
     * about 2 to 3 words a second; under 1 a second, over 2.5 seconds or more, means audio was
     * lost. (Slow talkers and laughter can trip this too; the cost is only a slower redo.)
     */
    static boolean thin(List<String> heard, double seconds) {
        if (seconds < 2.5) return false;
        int words = 0;
        for (String h : heard) for (String w : h.trim().split("\\s+")) if (!w.isEmpty()) words++;
        return words < seconds;
    }

    private Speed() {}
}
