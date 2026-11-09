package io.github.bags156.messagecenter;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;

public class TranscriptTextTest {
    @Test public void unpunctuatedPiecesBecomeSentences() {
        assertEquals("Hey it's me. I'm running late. Call me when you get this.",
            TranscriptText.join(Arrays.asList("hey it's me", "i'm running late", " call me when you get this "), true));
    }
    @Test public void punctuatedTextIsLeftAsTheRecognizerWroteIt() {
        assertEquals("Hey, it's me. I'm running late, so call me when you get this.",
            TranscriptText.join(Arrays.asList("Hey, it's me.", "I'm running late,", "so call me when you get this."), true));
    }
    @Test public void loneLowercaseIIsCapitalizedInEnglishOnly() {
        assertEquals("I think I'll go. i.e. later.", TranscriptText.join(Arrays.asList("I think i'll go.", "i.e. later."), true));
        assertEquals("Si i va.", TranscriptText.join(Arrays.asList("si i va"), false));
    }
    @Test public void emptyPiecesAreSkipped() {
        assertEquals("Okay.", TranscriptText.join(Arrays.asList("", "  ", null, "okay"), true));
        assertEquals("", TranscriptText.join(Arrays.asList(), true));
    }
}
