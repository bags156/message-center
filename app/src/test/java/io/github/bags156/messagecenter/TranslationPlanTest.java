package io.github.bags156.messagecenter;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationPlanTest {
    @Test public void languageVariantsAndPivotRoutes() {
        assertArrayEquals(new String[]{"es-en"},TranslationPlan.route("es-MX","en-US"));
        assertArrayEquals(new String[]{"fr-en","en-es"},TranslationPlan.route("fr","es"));
        assertArrayEquals(new String[0],TranslationPlan.route("en-GB","en-US"));
        assertArrayEquals(new String[0],TranslationPlan.route("auto","es"));
        assertEquals("he",TranslationPlan.language("iw"));
    }
    @Test public void automaticTranslationLeavesShortLinksNumbersAndOwnMessagesAlone() {
        assertTrue(TranslationPlan.automatic("¿Dónde estás? Te espero en la puerta del cine.",false,false));
        assertFalse(TranslationPlan.automatic("OK 👍",false,false));
        assertTrue("a short message can be translated (if language ID is sure)",TranslationPlan.automatic("je suis là",false,false));
        assertFalse(TranslationPlan.automatic("lol",false,false));
        // Short texts: very sure, or fairly sure with the bundled detector agreeing.
        assertEquals("fr",TranslationPlan.shortGuess("fr-FR",.93f,"",.9f));
        assertEquals("fr",TranslationPlan.shortGuess("fr",.7f,"fr",.9f));
        assertEquals("",TranslationPlan.shortGuess("fr",.7f,"es",.9f));
        assertEquals("",TranslationPlan.shortGuess("fr",.7f,"",.9f));
        assertEquals("",TranslationPlan.shortGuess("fr",.5f,"fr",.9f));
        assertEquals("",TranslationPlan.shortGuess("und",.99f,"",.9f));
        assertFalse(TranslationPlan.automatic("https://example.com/longlinkwithmanyletters",false,false));
        assertFalse(TranslationPlan.automatic("441223",false,false));
        assertFalse(TranslationPlan.automatic("A longer outgoing message",true,false));
        assertFalse(TranslationPlan.automatic("A longer system notice",false,true));
    }
    @Test public void automaticOutgoingTranslationHandlesShortTextAndLeavesSymbolsAlone() {
        assertTrue(TranslationPlan.outgoingText("Hi"));
        assertTrue(TranslationPlan.outgoingText("Meet me at https://example.com"));
        assertFalse(TranslationPlan.outgoingText("https://example.com"));
        assertFalse(TranslationPlan.outgoingText("123 👍"));
        assertFalse(TranslationPlan.outgoingText(""));
        assertEquals("en",TranslationPlan.outgoingSource("Hi","es","en-US"));
        assertEquals("en",TranslationPlan.outgoingSource("A longer message without a confident detection","","en"));
        assertEquals("es",TranslationPlan.outgoingSource("¿Dónde estás? Te espero en la puerta del cine.","es","en"));
    }
    @Test public void draftsAlreadyInTheSendingLanguageGoAsWritten() {
        assertTrue(TranslationPlan.alreadyIn("es","es"));
        assertTrue(TranslationPlan.alreadyIn("es","es-MX"));
        assertFalse(TranslationPlan.alreadyIn("en","es"));
        assertFalse(TranslationPlan.alreadyIn("","es"));
        assertFalse(TranslationPlan.alreadyIn("es",""));
        // Ordinary messages take language ID's usual answer; short ones need it very sure; a word or two, never.
        assertEquals(.6f,TranslationPlan.sureEnough("¿Nos vemos mañana en la casa?"),0f);
        assertEquals(.9f,TranslationPlan.sureEnough("Ya voy 👍"),0f);
        assertTrue(TranslationPlan.sureEnough("Sí")>1);
        assertTrue(TranslationPlan.sureEnough("ok https://example.com/somewhere-long")>1);
        // A translation that only touched punctuation, case or emoji is what was typed.
        assertTrue(TranslationPlan.unchanged("Ya voy","¡Ya voy!"));
        assertTrue(TranslationPlan.unchanged("gracias 🙏","Gracias."));
        assertFalse(TranslationPlan.unchanged("Si","Sí"));
        assertFalse(TranslationPlan.unchanged("I'm on my way","Ya voy"));
        assertFalse(TranslationPlan.unchanged("👍","👍"));
    }
    @Test public void outdatedTranslationCannotSendAfterAnyContextChanges() {
        assertTrue(TranslationPlan.current("a","hello","reply1","photo1","a","hello","reply1","photo1"));
        assertFalse(TranslationPlan.current("b","hello","reply1","photo1","a","hello","reply1","photo1"));
        assertFalse(TranslationPlan.current("a","changed","reply1","photo1","a","hello","reply1","photo1"));
        assertFalse(TranslationPlan.current("a","hello","reply2","photo1","a","hello","reply1","photo1"));
        assertFalse(TranslationPlan.current("a","hello","reply1","photo2","a","hello","reply1","photo1"));
    }
    @Test public void readingIsAppWideAndSendingStaysIndependentForEachChat() {
        MemoryPrefs prefs=new MemoryPrefs();ChatSettings first=new ChatSettings(prefs);
        first.setReadLanguage("en-US");first.setSendLanguage("a","es-MX");first.setSendLanguage("b","fr");
        ChatSettings restored=new ChatSettings(prefs);
        assertEquals("en",restored.readLanguage());assertEquals("es",restored.sendLanguage("a"));
        assertEquals("fr",restored.sendLanguage("b"));assertEquals("",restored.sendLanguage("c"));
        restored.setReadLanguage("");assertEquals("",restored.readLanguage());assertEquals("en",restored.lastReadLanguage());
        assertEquals("es",restored.sendLanguage("a"));assertEquals("fr",restored.sendLanguage("b"));
        assertEquals("",new ChatSettings(prefs).readLanguage());
    }
    @Test public void declaredWritingLanguageAvoidsIncorrectDetectionOfLongDrafts() {
        String words="Sam and I are on our way, but we're running a little late.";
        assertEquals("en",TranslationPlan.outgoingSource(words,"es","en-US",""));
        assertEquals("fr",TranslationPlan.outgoingSource(words,"es","en","fr"));
        assertEquals("es",TranslationPlan.outgoingSource(words,"es","en","auto"));
        MemoryPrefs prefs=new MemoryPrefs();ChatSettings settings=new ChatSettings(prefs);
        settings.setWriteLanguage("a","en-US");settings.setWriteLanguage("b","auto");settings.setSendLanguage("a","es");
        ChatSettings restored=new ChatSettings(prefs);
        assertEquals("en",restored.writeLanguage("a"));assertEquals("auto",restored.writeLanguage("b"));assertEquals("",restored.writeLanguage("c"));assertEquals("es",restored.sendLanguage("a"));
    }
    @Test public void existingReadingChoicesMigrateOnceWithoutChangingSendingLanguages() {
        MemoryPrefs prefs=new MemoryPrefs();prefs.edit().putString("translate_read:a","es").putString("translate_read:b","en-US")
            .putString("translate_read:c","en").putString("translate_read:d","").putString("translate_send:a","fr").apply();
        ChatSettings settings=new ChatSettings(prefs);
        assertEquals("en",settings.readLanguage());assertEquals("en",settings.lastReadLanguage());assertEquals("fr",settings.sendLanguage("a"));
        settings.setReadLanguage("");assertEquals("",new ChatSettings(prefs).readLanguage());
        settings.setReadLanguage("de");assertEquals("de",new ChatSettings(prefs).readLanguage());
    }
    @Test public void readingMigrationStartsOffAndResolvesMixedLanguageTiesConsistently() {
        MemoryPrefs prefs=new MemoryPrefs();assertEquals("",new ChatSettings(prefs).readLanguage());
        MemoryPrefs mixed=new MemoryPrefs();mixed.edit().putString("translate_read:z","es").putString("translate_read:a","fr").apply();
        assertEquals("es",new ChatSettings(mixed).readLanguage());
    }
}
