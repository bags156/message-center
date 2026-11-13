package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChatThemeTest {
    @Test public void presetsAreUnchanged() {
        ChatTheme ocean = ChatTheme.of("ocean");
        assertEquals(0xff1d5c63, ocean.bubble(true));
        assertEquals(0xffc2ebe8, ocean.bubble(false));
        assertEquals(0xfff1f0f7, ocean.onBubble(true));
        assertEquals(0xff1a1b20, ocean.onBubble(false));
        assertEquals(0xff102033, ocean.onAccent(true));
        assertFalse(ocean.custom());
    }

    @Test public void yourColorExactlyWhereYouPickedIt() {
        ChatTheme dark = ChatTheme.custom(0xff3366aa, true);
        assertEquals(0xff3366aa, dark.bubble(true));
        assertTrue("a lighter shade for light mode", ChatTheme.luminance(dark.bubble(false)) > 0.5);
        ChatTheme light = ChatTheme.custom(0xffffc0cb, false);
        assertEquals(0xffffc0cb, light.bubble(false));
        assertTrue("a deeper shade for dark mode", ChatTheme.luminance(light.bubble(true)) < 0.1);
    }

    @Test public void keysRoundTrip() {
        ChatTheme t = ChatTheme.custom(0xff3366aa, true);
        assertEquals("custom:3366aa:d", t.key);
        assertTrue(t.custom());
        ChatTheme back = ChatTheme.of(t.key);
        assertEquals(t.bubble(true), back.bubble(true));
        assertEquals(t.bubble(false), back.bubble(false));
        assertEquals(t.accent(true), back.accent(true));
        assertEquals(0xff3366aa, ChatTheme.customColor(t.key));
        assertEquals("custom:ffc0cb:l", ChatTheme.custom(0xffffc0cb, false).key);
        assertEquals(ChatTheme.ALL.get(0), ChatTheme.of("custom:zzzzzz:d"));
        assertEquals(ChatTheme.ALL.get(0), ChatTheme.of("custom:"));
        assertEquals(ChatTheme.ALL.get(0), ChatTheme.of(null));
    }

    @Test public void anyColorStaysReadableAndTheSendButtonStandsOut() {
        for (int h = 0; h < 360; h += 15) for (float s : new float[]{0f, 0.4f, 1f}) for (float l : new float[]{0f, 0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1f}) {
            int color = ChatTheme.fromHsl(h, s, l);
            for (boolean picked : new boolean[]{true, false}) {
                ChatTheme t = ChatTheme.custom(color, picked);
                for (boolean dark : new boolean[]{true, false}) {
                    String what = Integer.toHexString(color) + (picked ? " picked dark" : " picked light") + (dark ? ", dark" : ", light");
                    assertTrue("words readable on " + what, ChatTheme.contrast(t.onBubble(dark), t.bubble(dark)) >= 3.5);
                    assertTrue("send icon readable on " + what, ChatTheme.contrast(t.onAccent(dark), t.accent(dark)) >= 3.0);
                    int background = dark ? ChatTheme.DARK_BACKGROUND : ChatTheme.LIGHT_BACKGROUND;
                    assertTrue("send button stands out on " + what, ChatTheme.contrast(t.accent(dark), background) >= 3.0);
                }
                assertTrue("send button stands out on black too", ChatTheme.contrast(t.accent(true), 0xff000000) >= 3.0);
            }
        }
    }

    @Test public void hslRoundTrips() {
        for (int c : new int[]{0xff000000, 0xffffffff, 0xff3366aa, 0xffe53935, 0xff43a047, 0xff808080, 0xffffc0cb}) {
            float[] hsl = ChatTheme.hsl(c);
            int back = ChatTheme.fromHsl(hsl[0], hsl[1], hsl[2]);
            for (int shift = 0; shift <= 16; shift += 8) assertTrue(Integer.toHexString(c) + " → " + Integer.toHexString(back), Math.abs(((c >> shift) & 0xff) - ((back >> shift) & 0xff)) <= 1);
        }
    }
}
