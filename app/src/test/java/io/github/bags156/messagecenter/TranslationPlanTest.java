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
        assertFalse(TranslationPlan.automatic("https://example.com/longlinkwithmanyletters",false,false));
        assertFalse(TranslationPlan.automatic("441223",false,false));
        assertFalse(TranslationPlan.automatic("A longer outgoing message",true,false));
        assertFalse(TranslationPlan.automatic("A longer system notice",false,true));
    }
    @Test public void stalePreviewCannotSendAfterAnyContextChanges() {
        assertTrue(TranslationPlan.current("a","hello","reply1","photo1","a","hello","reply1","photo1"));
        assertFalse(TranslationPlan.current("b","hello","reply1","photo1","a","hello","reply1","photo1"));
        assertFalse(TranslationPlan.current("a","changed","reply1","photo1","a","hello","reply1","photo1"));
        assertFalse(TranslationPlan.current("a","hello","reply2","photo1","a","hello","reply1","photo1"));
        assertFalse(TranslationPlan.current("a","hello","reply1","photo2","a","hello","reply1","photo1"));
    }
    @Test public void readAndSendDefaultsAreIndependentAndScopedToChat() {
        MemoryPrefs prefs=new MemoryPrefs();ChatSettings first=new ChatSettings(prefs);
        first.setReadLanguage("a","en-US");first.setSendLanguage("a","es-MX");
        ChatSettings restored=new ChatSettings(prefs);
        assertEquals("en",restored.readLanguage("a"));assertEquals("es",restored.sendLanguage("a"));
        assertEquals("",restored.sendLanguage("b"));assertEquals("",restored.readLanguage("b"));
        restored.setReadLanguage("a","");assertEquals("",restored.readLanguage("a"));assertEquals("es",restored.sendLanguage("a"));
    }
}
