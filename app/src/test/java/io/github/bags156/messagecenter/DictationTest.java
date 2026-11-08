package io.github.bags156.messagecenter;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Dictated words land with the right spacing and capitals. */
public class DictationTest {
    @Test public void startOfMessageIsCapitalized() {
        assertEquals("On my way", ChatText.dictation("", "on my way"));
    }
    @Test public void continuesASentenceWithASpace() {
        assertEquals(" see you soon", ChatText.dictation("Running late,", "see you soon"));
        assertEquals("see you soon", ChatText.dictation("Running late, ", "see you soon"));
    }
    @Test public void newSentenceIsCapitalized() {
        assertEquals(" Can you grab milk", ChatText.dictation("Heading home.", "can you grab milk"));
        assertEquals("Sounds good", ChatText.dictation("Really?\n", "sounds good"));
    }
    @Test public void nothingHeardAddsNothing() {
        assertEquals("", ChatText.dictation("Hi", "   "));
        assertEquals("", ChatText.dictation("Hi", null));
    }
}
