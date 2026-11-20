package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * The emojis you react with most, for the message menu's second row (the first stays the usual
 * ones). Counted on this phone each time you react; most used first, the more recent first on a tie.
 */
final class ReactionUsage {
    private ReactionUsage() {}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("reactions_used", Context.MODE_PRIVATE); }

    /** You reacted with this emoji. */
    static void used(Context c, String emoji) {
        if (emoji == null || emoji.trim().isEmpty()) return;
        SharedPreferences p = prefs(c);
        try {
            JSONObject was = new JSONObject(p.getString(emoji, "{}"));
            p.edit().putString(emoji, new JSONObject().put("n", was.optInt("n") + 1).put("at", System.currentTimeMillis()).toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Your most-used emojis that aren't in the first row (or already your reaction), at most max. */
    static List<String> row(Context c, Collection<String> notThese, int max) {
        Map<String, int[]> counts = new HashMap<>();
        Map<String, Long> at = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            try { JSONObject o = new JSONObject(String.valueOf(e.getValue())); counts.put(e.getKey(), new int[]{o.optInt("n")}); at.put(e.getKey(), o.optLong("at")); } catch (Exception ignored) {}
        }
        return pick(counts, at, notThese, max);
    }

    /** Pure ordering, for unit tests: most used, then most recent; never one of notThese. */
    static List<String> pick(Map<String, int[]> counts, Map<String, Long> at, Collection<String> notThese, int max) {
        List<String> out = new ArrayList<>();
        for (String e : counts.keySet()) {
            boolean skip = false;
            for (String n : notThese) if (ReactionParser.sameEmoji(e, n)) { skip = true; break; }
            if (!skip && counts.get(e)[0] > 0) out.add(e);
        }
        out.sort((a, b) -> {
            int byCount = Integer.compare(counts.get(b)[0], counts.get(a)[0]);
            return byCount != 0 ? byCount : Long.compare(at.getOrDefault(b, 0L), at.getOrDefault(a, 0L));
        });
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    static List<String> list(String... emojis) { return Arrays.asList(emojis); }
}
