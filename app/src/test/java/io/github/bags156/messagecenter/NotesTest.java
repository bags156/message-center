package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotesTest {
    @Test public void aPersonsNotesFollowTheirNumber() {
        assertEquals("p:4795550100", Notes.personKey("+1 (479) 555-0100"));
        assertEquals(Notes.personKey("+14795550100"), Notes.personKey("479-555-0100"));
        assertEquals("", Notes.personKey(""));
        assertEquals("", Notes.personKey(null));
        assertEquals("g:abc", Notes.groupKey("abc"));
        assertEquals("", Notes.groupKey(""));
    }

    @Test public void linesGoOnTheEnd() {
        assertEquals("Gift: scarf", Notes.appended("", "Gift: scarf"));
        assertEquals("Gift: scarf", Notes.appended(null, "  Gift: scarf "));
        assertEquals("Birthday May 3\nGift: scarf", Notes.appended("Birthday May 3\n\n  ", "Gift: scarf"));
        assertEquals("Birthday May 3", Notes.appended("Birthday May 3", "   "));
    }

    @Test public void savedMessagesSayWhenAndWhose() {
        assertEquals("• Nov 12: Wants the blue scarf", Notes.clip("Nov 12", "", "Wants the  blue\nscarf "));
        assertEquals("• Nov 12 (you): I'll get tickets", Notes.clip("Nov 12", "(you)", "I'll get tickets"));
        assertEquals("• Nov 12 in Family: Starting a new job Monday", Notes.clip("Nov 12", "in Family", "Starting a new job Monday"));
    }

    @Test public void searchFindsTheMatchingLine() {
        String notes = "Birthday: May 3\nGift ideas: blue scarf, Café Bustelo\nAsk how the new job is going";
        assertTrue(Notes.matches(notes, "SCARF"));
        assertTrue(Notes.matches(notes, "cafe"));
        assertFalse(Notes.matches(notes, "tickets"));
        assertFalse(Notes.matches(notes, "  "));
        assertEquals("Gift ideas: blue scarf, Café Bustelo", Notes.snippet(notes, "scarf"));
        assertEquals("Ask how the new job is going", Notes.snippet(notes, "job"));
        assertEquals("Birthday: May 3", Notes.snippet(notes, "nothing here"));
        String long1 = "Things to remember: " + "x".repeat(80) + " the anniversary dinner place " + "y".repeat(80);
        String cut = Notes.snippet(long1, "anniversary");
        assertTrue(cut, cut.contains("anniversary") && cut.startsWith("…") && cut.endsWith("…") && cut.length() <= 92);
    }
}
