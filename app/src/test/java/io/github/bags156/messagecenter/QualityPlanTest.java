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
    @Test public void casualSpanishGetsOneGenderFormAndClosedMarks() {
        assertEquals("¡Suena bien! 👍",QualityPlan.tidy("¡Suena bien 👍","es"));
        assertEquals("¿Viste? ¡Estoy muerto de la risa! 💀",QualityPlan.tidy("¿Viste? ¡Estoy muerto de la risa 💀","es"));
        assertEquals("¡Qué bien!",QualityPlan.tidy("¡Qué bien.","es"));
        assertEquals("Tranquilo, ¡tómate tu tiempo!",QualityPlan.tidy("Tranquilo/a, ¡tómate tu tiempo!","es"));
        assertEquals("Estoy agotado, me voy a dormir.",QualityPlan.tidy("Estoy agotad@, me voy a dormir.","es"));
        assertEquals("Los niños",QualityPlan.tidy("Los/as niños/as","es"));
        // Left alone: emails, already-closed or nested marks, and other languages.
        assertEquals("Escríbeme a ana@example.com",QualityPlan.tidy("Escríbeme a ana@example.com","es"));
        assertEquals("¡Hola, ¿cómo estás?",QualityPlan.tidy("¡Hola, ¿cómo estás?","es"));
        assertEquals("Te quiero, ¡nos vemos pronto! ❤️",QualityPlan.tidy("Te quiero, ¡nos vemos pronto! ❤️","es"));
        assertEquals("tired/a",QualityPlan.tidy("tired/a","en"));
    }
    @Test public void textingPromptDependsOnlyOnTheLanguages() {
        String casual=QualityPlan.prefix("en","es");
        assertTrue(casual.contains("casual text message")&&casual.contains("tú")&&casual.contains("Keep every emoji"));
        assertEquals(casual,QualityPlan.prefix("en","es",true));
        assertFalse(QualityPlan.prefix("en","es",false).contains("casual"));
        assertFalse(QualityPlan.prefix("es","en").contains("tú"));
        assertTrue(QualityPlan.prefix("es","en").contains("Latin American slang"));
        assertTrue(casual.startsWith("<start_of_turn>user\n")&&casual.endsWith(":\n\n\n"));
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
