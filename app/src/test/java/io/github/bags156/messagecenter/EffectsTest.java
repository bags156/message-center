package io.github.bags156.messagecenter;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** iPhone effect notes are found and removed; phrases set off their effects. */
public class EffectsTest {
    @Test public void screenEffectNote() {
        Effects.Note n = Effects.note("Happy birthday! (sent with Balloons)");
        assertEquals("Happy birthday!", n.text);
        assertEquals(Effects.BALLOONS, n.effect);
    }
    @Test public void bubbleEffectNotesWithAndWithoutTheWordEffect() {
        assertEquals(Effects.SLAM, Effects.note("I'm here (sent with Slam effect)").effect);
        assertEquals(Effects.LOUD, Effects.note("WHAT (Sent with Loud Effect)").effect);
        assertEquals(Effects.GENTLE, Effects.note("sorry (sent with Gentle Effect)").effect);
        assertEquals("I'm here", Effects.note("I'm here (sent with Slam effect)").text);
    }
    @Test public void twoWordEffects() {
        assertEquals(Effects.INK, Effects.note("It's a girl (sent with Invisible Ink)").effect);
        assertEquals(Effects.SHOOTING_STAR, Effects.note("Wish! (sent with Shooting Star)").effect);
    }
    @Test public void noteOnItsOwnLine() {
        Effects.Note n = Effects.note("We did it\n(sent with Confetti)");
        assertEquals("We did it", n.text);
        assertEquals(Effects.CONFETTI, n.effect);
    }
    @Test public void noteWithNoText() {
        Effects.Note n = Effects.note("(sent with Fireworks)");
        assertEquals("", n.text);
        assertEquals(Effects.FIREWORKS, n.effect);
        assertEquals("Sent with Fireworks", Effects.preview("(sent with Fireworks)"));
    }
    @Test public void ordinaryTextIsLeftAlone() {
        assertEquals("", Effects.note("I sent with care (and love)").effect);
        assertEquals("Lunch (sent with Bob) was fun", Effects.note("Lunch (sent with Bob) was fun").text);
        assertEquals("Package (sent with UPS)", Effects.note("Package (sent with UPS)").text);
        assertEquals("", Effects.note("Package (sent with UPS)").effect);
        assertEquals("", Effects.note(null).text);
    }
    @Test public void inkStaysHiddenInPreviews() {
        assertEquals("Sent with invisible ink", Effects.preview("Big news (sent with Invisible Ink)"));
        assertEquals("Big news", Effects.preview("Big news (sent with Love)"));
    }
    @Test public void phrases() {
        assertEquals(Effects.BALLOONS, Effects.phrase("Happy Birthday Sam!!"));
        assertEquals(Effects.BALLOONS, Effects.phrase("happy bday"));
        assertEquals(Effects.CONFETTI, Effects.phrase("Congrats on the new job"));
        assertEquals(Effects.CONFETTI, Effects.phrase("CONGRATULATIONS"));
        assertEquals(Effects.FIREWORKS, Effects.phrase("Happy New Year!"));
        assertEquals(Effects.CELEBRATION, Effects.phrase("Happy Lunar New Year"));
        assertEquals(Effects.LASERS, Effects.phrase("pew pew"));
        assertEquals("", Effects.phrase("Happy to help"));
        assertEquals("", Effects.phrase("unhappy birthdays"));
    }
    @Test public void kindsAndLabels() {
        assertEquals(true, Effects.fullScreen(Effects.ECHO));
        assertEquals(false, Effects.fullScreen(Effects.SLAM));
        assertEquals(false, Effects.fullScreen(Effects.INK));
        assertEquals(false, Effects.fullScreen(""));
        assertEquals("Shooting Star", Effects.label(Effects.SHOOTING_STAR));
    }
}
