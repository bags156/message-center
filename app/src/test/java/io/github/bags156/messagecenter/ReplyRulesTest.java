package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ReplyRulesTest {
    private static String first(String message) { List<String> r = ReplyRules.suggest(message); return r.isEmpty() ? "" : r.get(0); }

    @Test public void commonMessages() {
        assertEquals("You're welcome!", first("Thanks so much!!"));
        assertEquals("Sure!", first("Want to grab lunch tomorrow?"));
        assertEquals("Sure!", first("Are you free Saturday?"));
        assertEquals("Yes", first("Did you get my email?"));
        assertEquals("Good, you?", first("How's it going"));
        assertEquals("Hey!", first("hey"));
        assertEquals("Coming!", first("I'm here"));
        assertEquals("On my way", first("Where are you?"));
        assertEquals("Good night!", first("good night!"));
        assertEquals("No worries!", first("Sorry I missed your call"));
        assertEquals("Thank you!", first("Happy birthday!! 🎉"));
        assertEquals(3, ReplyRules.suggest("Thanks").size());
    }

    @Test public void nothingWhenNothingFits() {
        assertTrue(ReplyRules.suggest("The package came today").isEmpty());
        assertTrue(ReplyRules.suggest("What time is dinner?").isEmpty());
        assertTrue(ReplyRules.suggest("¿Dónde estás? Te espero en la puerta del cine.").isEmpty());
        assertTrue(ReplyRules.suggest("Привет, как дела?").isEmpty());
        assertTrue(ReplyRules.suggest("").isEmpty());
        assertTrue(ReplyRules.suggest(null).isEmpty());
    }

    @Test public void wordsNotParts() {
        // "ty" in "party" isn't thanks; "gn" in "sign" isn't good night.
        assertTrue(ReplyRules.suggest("The party starts at 8").isEmpty());
        assertTrue(ReplyRules.suggest("Please sign the form").isEmpty());
    }
}
