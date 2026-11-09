package io.github.bags156.messagecenter;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

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
}
