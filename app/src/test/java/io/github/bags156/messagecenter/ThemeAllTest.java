package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** One color for every chat, with any chat still free to have its own. */
public class ThemeAllTest {
    @Test public void chatsFollowTheAllChatsColorUnlessTheyHaveTheirOwn() {
        ChatSettings s = new ChatSettings(new MemoryPrefs());
        assertEquals("default", s.theme("a").key);
        s.setTheme("b", "berry");
        s.themeAll("ocean", false);
        assertEquals("ocean", s.theme("a").key);
        assertEquals("kept its own", "berry", s.theme("b").key);
        assertTrue(s.ownTheme("b"));
        assertFalse(s.ownTheme("a"));
        assertEquals("ocean", s.theme("").key);
    }

    @Test public void changingAllCanSwitchTheOnesWithTheirOwnToo() {
        ChatSettings s = new ChatSettings(new MemoryPrefs());
        s.setTheme("b", "berry");
        s.setTheme("c", "custom:e91e63:d");
        assertEquals(2, s.ownThemes().size());
        s.themeAll("forest", true);
        assertEquals("forest", s.theme("b").key);
        assertEquals("forest", s.theme("c").key);
        assertTrue(s.ownThemes().isEmpty());
    }

    @Test public void oneChatCanStillBeChangedAfterAndGoBack() {
        ChatSettings s = new ChatSettings(new MemoryPrefs());
        s.themeAll("forest", true);
        s.setTheme("a", "rose");
        assertEquals("rose", s.theme("a").key);
        assertEquals("forest", s.theme("z").key);
        s.sameAsAll("a");
        assertEquals("forest", s.theme("a").key);
        s.setTheme("", "gold");
        assertEquals("setting \"\" changes the all-chats color", "gold", s.theme("a").key);
        assertEquals("custom colors work for all chats", "custom:3366aa:d", ((java.util.function.Supplier<String>) () -> { s.setTheme("", "custom:3366aa:d"); return s.theme("q").key; }).get());
    }
}
