package io.github.bags156.messagecenter;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** What you type in New conversation finds the right people, best matches first. */
public class ContactSearchTest {
    private static int score(String name, String typed) {
        return ContactSearch.score(ContactSearch.fold(name), "", "14795550123", ContactSearch.fold(typed), ChatText.digits(typed));
    }
    @Test public void nameStartBeatsLaterWordBeatsInside() {
        assertEquals(0, score("Hannah Kay", "han"));
        assertEquals(1, score("Hannah Kay", "kay"));
        assertEquals(2, score("Hannah Kay", "nna"));
        assertEquals(-1, score("Hannah Kay", "bob"));
    }
    @Test public void caseAndAccentsDontMatter() {
        assertEquals(0, score("José Álvarez", "jose"));
        assertEquals(1, score("José Álvarez", "ALVAREZ"));
    }
    @Test public void punctuationSeparatesWords() {
        assertEquals(1, score("Mary-Kate O'Neil", "kate"));
        assertEquals(1, score("Dr. (Sam) Lee", "sam"));
    }
    @Test public void numbersNeedThreeDigits() {
        assertEquals(3, score("Hannah Kay", "479"));
        assertEquals(3, score("Hannah Kay", "(479) 555"));
        assertEquals(-1, score("Hannah Kay", "47"));
        assertEquals(-1, score("Hannah Kay", "999"));
    }
    @Test public void nicknamesCount() {
        assertEquals(0, ContactSearch.score("hannah kay", ContactSearch.fold("Wifey"), "1", "wif", ""));
    }
    @Test public void emptyQueryMatchesEveryone() {
        assertEquals(0, score("Anyone", ""));
    }
    @Test public void numbersWithoutNamesAreRecognized() {
        for (String n : new String[]{"", "  ", "+1 555-010-0123", "(555) 010-0123", "\u202a+1 555 010 0123\u202c", "5550100123"}) assertTrue(n, ContactSearch.numberLike(n));
        for (String n : new String[]{"Hannah Kay", "Mom", "Dr. Lee", "Apt 4B", "7-Eleven"}) assertFalse(n, ContactSearch.numberLike(n));
        assertTrue(ContactSearch.numberLike(null));
    }

    @Test public void theSameNumberWrittenDifferentlyMatches() {
        assertEquals(ContactSearch.key("+1 (555) 010-0123"), ContactSearch.key("5550100123"));
        assertEquals(ContactSearch.key("+15550100123"), ContactSearch.key("\u202a+1 555 010 0123\u202c"));
        assertEquals("", ContactSearch.key(null));
        assertNotEquals(ContactSearch.key("+15550100123"), ContactSearch.key("+15550100124"));
    }
}
