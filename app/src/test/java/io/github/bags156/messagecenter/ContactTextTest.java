package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ContactTextTest {
    @Test public void nameAndNumber() {
        assertEquals("Ben Smith: (479) 555-0134", ContactText.of(" Ben Smith ", "(479) 555-0134", ""));
        assertEquals("Ben Smith: (479) 555-0134, ben@example.com", ContactText.of("Ben Smith", "(479) 555-0134", "ben@example.com"));
    }

    @Test public void noNameMeansJustTheNumber() {
        assertEquals("(479) 555-0134", ContactText.of("", "(479) 555-0134", null));
        // A contact with no name shows its number as the name.
        assertEquals("+1 479-555-0134", ContactText.of("+14795550134", "+1 479-555-0134", ""));
        assertEquals("(479) 555-0134, ben@example.com", ContactText.of(null, "(479) 555-0134", "ben@example.com"));
    }

    @Test public void goesOnItsOwnLineNextToWords() {
        ContactText.Inserted a = ContactText.insert("", -1, -1, "Ben: 1");
        assertEquals("Ben: 1", a.text); assertEquals(6, a.cursor);
        ContactText.Inserted b = ContactText.insert("Here's his number", 17, 17, "Ben: 1");
        assertEquals("Here's his number\nBen: 1", b.text); assertEquals(24, b.cursor);
        ContactText.Inserted c = ContactText.insert("Call him ", 9, 9, "Ben: 1");
        assertEquals("Call him Ben: 1", c.text);
        ContactText.Inserted d = ContactText.insert("Thanks", 0, 0, "Ben: 1");
        assertEquals("Ben: 1\nThanks", d.text); assertEquals(6, d.cursor);
        // Replaces a selection.
        ContactText.Inserted e = ContactText.insert("Call XXX now", 5, 8, "Ben: 1");
        assertEquals("Call Ben: 1 now", e.text);
    }
}
