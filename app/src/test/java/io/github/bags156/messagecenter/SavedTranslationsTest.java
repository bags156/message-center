package io.github.bags156.messagecenter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A saved translation shows again unless the message changed, or (if it was automatic) you now read in another language or with another translator. */
public class SavedTranslationsTest {
    @Test public void savedTranslationsApplyUntilSomethingChanges() {
        SavedTranslations.Saved auto = new SavedTranslations.Saved("I'm here", "French", "en", "je suis là", false, true, "stronger-q8");
        assertTrue(SavedTranslations.applies(auto, "je suis là", "en", "stronger-q8"));
        assertFalse("edited since", SavedTranslations.applies(auto, "je suis là !", "en", "stronger-q8"));
        assertFalse("now reading in Spanish", SavedTranslations.applies(auto, "je suis là", "es", "stronger-q8"));
        assertFalse("now using the other translator", SavedTranslations.applies(auto, "je suis là", "en", "compact"));
        SavedTranslations.Saved asked = new SavedTranslations.Saved("Estoy aquí", "French", "es", "je suis là", true, false, "compact");
        assertTrue("one you asked for stays", SavedTranslations.applies(asked, "je suis là", "en", "stronger-q8"));
        assertFalse(SavedTranslations.applies(null, "je suis là", "en", "compact"));
    }
}
