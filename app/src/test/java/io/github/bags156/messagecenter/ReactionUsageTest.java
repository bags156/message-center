package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ReactionUsageTest {
    /** Most used first, the more recent first on a tie, never one already in the first row, at most max. */
    @Test public void mostUsedFirstAndNoRepeatsOfTheFirstRow() {
        Map<String, int[]> counts = new HashMap<>();
        Map<String, Long> at = new HashMap<>();
        counts.put("🔥", new int[]{5}); at.put("🔥", 10L);
        counts.put("🙏", new int[]{2}); at.put("🙏", 30L);
        counts.put("💯", new int[]{2}); at.put("💯", 20L);
        counts.put("❤️", new int[]{9}); at.put("❤️", 40L);
        counts.put("👀", new int[]{1}); at.put("👀", 50L);
        List<String> row = ReactionUsage.pick(counts, at, ReactionUsage.list("❤️", "👍"), 3);
        assertEquals(List.of("🔥", "🙏", "💯"), row);
    }
}
