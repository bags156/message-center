package io.github.bags156.messagecenter;

import org.junit.Test;
import static org.junit.Assert.*;

public class QualityPlanTest {
    @Test public void longMultilineMessagesKeepEveryCharacterAndSurrogatePair() {
        String source="Hello 🙂. ".repeat(230)+"\n\nSecond paragraph, https://example.com.\n";
        StringBuilder joined=new StringBuilder();
        for(String part:QualityPlan.fragments(source)){assertTrue(part.length()<=800);assertFalse(Character.isHighSurrogate(part.charAt(part.length()-1)));joined.append(part);}
        assertEquals(source,joined.toString());
    }
    @Test public void alteredLinksCodesTimesAndMissingEmojiAreRejected() {
        String source="Sam: 7:30 🙂 https://example.com/a?x=1&y=2 code 123456";
        assertTrue(QualityPlan.preserves(source,"Sam: 7:30 🙂 https://example.com/a?x=1&y=2 código 123456"));
        assertFalse(QualityPlan.preserves(source,"Sam: 7:30 🙂 https://example.com/b?x=1&y=2 código 123456"));
        assertFalse(QualityPlan.preserves(source,"Sam: 7:30 🙂 https://example.com/a?x=1&y=2 código 654321"));
        assertFalse(QualityPlan.preserves(source,"Sam: 8:30 🙂 https://example.com/a?x=1&y=2 código 123456"));
        assertFalse(QualityPlan.preserves(source,"Sam: 7:30 https://example.com/a?x=1&y=2 código 123456"));
    }
    @Test public void controlTokensAndBlankOutputsAreNotTranslations() {
        assertFalse(QualityPlan.preserves("Hello", " "));
        assertFalse(QualityPlan.preserves("Hello", "Hola<end_of_turn>"));
        assertFalse(QualityPlan.preserves("Contact sam@example.com", "Contacta a sam@other.com"));
        assertTrue(QualityPlan.preserves("Hello 🙂🙂", "Hola 🙂🙂"));
        assertFalse(QualityPlan.preserves("Hello 🙂🙂", "Hola 🙂"));
    }
    @Test public void joinedEmojiFlagsAndAddedReactionsKeepTheirIdentity() {
        assertTrue(QualityPlan.preserves("Hello 👩‍🚀 🇺🇸 1️⃣", "Hola 👩‍🚀 🇺🇸 1️⃣"));
        assertFalse(QualityPlan.preserves("Hello 👩‍🚀", "Hola 👩🚀"));
        assertFalse(QualityPlan.preserves("Hello 🇺🇸", "Hola 🇸🇺"));
        assertFalse(QualityPlan.preserves("Hello ❤️", "Hola ❤"));
        assertFalse(QualityPlan.preserves("Hello", "Hola ❤️"));
        assertFalse(QualityPlan.preserves("Hello 🙂", "Hola 🙂🙂"));
    }

}
