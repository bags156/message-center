package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FontsTest {
    @Test public void namesFromFiles() {
        assertEquals("Inter Regular", Fonts.cleanName("Inter-Regular.ttf"));
        assertEquals("Comic Neue", Fonts.cleanName("Comic_Neue.otf"));
        assertEquals("SF Pro Display", Fonts.cleanName("  SF  Pro   Display.TTF "));
        assertEquals("Your font", Fonts.cleanName(".ttf"));
        assertEquals("Your font", Fonts.cleanName(null));
        assertEquals(40, Fonts.cleanName("A very very very long font file name that goes on and on.ttf").length());
    }
}
