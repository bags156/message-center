package io.github.bags156.messagecenter;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class InboxOrderTest {
    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Chicago");

    private static JSONObject chat(String id, long time, boolean pinned) throws Exception {
        return new JSONObject().put("conversationID", id).put("lastMessageTimestamp", time * 1000).put("pinned", pinned);
    }
    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(ZONE); c.clear(); c.set(year, month - 1, day, hour, minute); return c.getTimeInMillis();
    }

    @Test public void pinnedChatsComeFirstThenNewest() throws Exception {
        List<JSONObject> chats = Arrays.asList(chat("old", 1_000_000_000_000L, false), chat("pinOld", 900_000_000_000L, true),
            chat("new", 1_700_000_000_000L, false), chat("pinNew", 1_600_000_000_000L, true));
        List<JSONObject> sorted = InboxOrder.sort(chats, c -> c.optBoolean("pinned"));
        assertEquals(Arrays.asList("pinNew", "pinOld", "new", "old"), sorted.stream().map(c -> c.optString("conversationID")).collect(java.util.stream.Collectors.toList()));
        assertEquals("old", chats.get(0).optString("conversationID"));
    }

    @Test public void localPinOverridesGoogle() {
        assertTrue(ChatSettings.effectivePin("1", false));
        assertFalse(ChatSettings.effectivePin("0", true));
        assertTrue(ChatSettings.effectivePin("", true));
        assertFalse(ChatSettings.effectivePin("", false));
    }

    @Test public void stampsFollowAge() {
        long now = at(2026, 11, 6, 23, 13);
        assertEquals(InboxOrder.Stamp.TIME, InboxOrder.stamp(at(2026, 11, 6, 9, 46), now, ZONE));
        assertEquals(InboxOrder.Stamp.WEEKDAY, InboxOrder.stamp(at(2026, 11, 5, 18, 0), now, ZONE));
        assertEquals(InboxOrder.Stamp.WEEKDAY, InboxOrder.stamp(at(2026, 10, 31, 0, 5), now, ZONE));
        assertEquals(InboxOrder.Stamp.MONTH_DAY, InboxOrder.stamp(at(2026, 10, 23, 12, 0), now, ZONE));
        assertEquals(InboxOrder.Stamp.FULL_DATE, InboxOrder.stamp(at(2025, 12, 30, 12, 0), now, ZONE));
    }

    @Test public void separatorsAfterAnHourOrNewDay() {
        assertTrue(InboxOrder.separator(0, at(2026, 11, 6, 11, 1), ZONE));
        assertFalse(InboxOrder.separator(at(2026, 11, 6, 11, 1), at(2026, 11, 6, 11, 48), ZONE));
        assertTrue(InboxOrder.separator(at(2026, 11, 6, 9, 0), at(2026, 11, 6, 11, 1), ZONE));
        assertTrue(InboxOrder.separator(at(2026, 11, 5, 23, 50), at(2026, 11, 6, 0, 10), ZONE));
    }

    @Test public void groupingNeedsSameSenderAndShortGap() {
        long t = at(2026, 11, 6, 11, 0);
        assertTrue(InboxOrder.grouped(true, "", t, true, "", t + 60_000));
        assertFalse(InboxOrder.grouped(true, "", t, false, "p2", t + 60_000));
        assertFalse(InboxOrder.grouped(false, "p2", t, false, "p3", t + 60_000));
        assertFalse(InboxOrder.grouped(true, "", t, true, "", t + 10 * 60_000));
        assertFalse(InboxOrder.grouped(true, "", 0, true, "", t));
    }

    @Test public void themesFallBackToDefaultAndKeepTextReadable() {
        assertEquals("default", ChatTheme.of("missing").key);
        assertEquals("ocean", ChatTheme.of("ocean").key);
        for (ChatTheme t : ChatTheme.ALL) {
            assertTrue(t.key + " dark", contrast(t.bubble(true), t.onBubble(true)) >= 4.5);
            assertTrue(t.key + " light", contrast(t.bubble(false), t.onBubble(false)) >= 4.5);
            assertTrue(t.key + " accent dark", contrast(t.accent(true), t.onAccent(true)) >= 3);
            assertTrue(t.key + " accent light", contrast(t.accent(false), t.onAccent(false)) >= 3);
        }
    }

    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
    private static double luminance(int c) {
        double[] v = {((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }
}
