package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;

public class VCardTest {
    @Test public void iphoneCard() {
        VCard c = VCard.parse("BEGIN:VCARD\r\nVERSION:3.0\r\nN:Doe;Jane;;;\r\nFN:Jane Doe\r\nORG:Acme\\, Inc.;\r\nitem1.TEL;type=CELL;type=pref:+1 (479) 555-0100\r\nTEL;type=WORK:479-555-0199\r\nEMAIL;type=INTERNET:jane@example.com\r\nEND:VCARD\r\n");
        assertEquals("Jane Doe", c.name);
        assertEquals(List.of("+1 (479) 555-0100", "479-555-0199"), c.phones);
        assertEquals(List.of("jane@example.com"), c.emails);
        assertEquals("Acme, Inc.", c.org);
    }

    @Test public void androidQuotedPrintableAndStructuredName() {
        VCard c = VCard.parse("BEGIN:VCARD\nVERSION:2.1\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:M=C3=BCller;J=C3=B6rg;;;\nTEL;CELL:5550100\nEND:VCARD\n");
        assertEquals("Jörg Müller", c.name);
        assertEquals(List.of("5550100"), c.phones);
    }

    @Test public void foldedLinesAndOnlyTheFirstCard() {
        VCard c = VCard.parse("BEGIN:VCARD\nVERSION:4.0\nFN:Hannah\n  Kay\nTEL;VALUE=uri:tel:+15550101\nEND:VCARD\nBEGIN:VCARD\nFN:Someone Else\nEND:VCARD\n");
        assertEquals("Hannah Kay", c.name);
        assertEquals(List.of("+15550101"), c.phones);
    }

    @Test public void emptyOrGarbage() {
        assertTrue(VCard.parse("").empty());
        assertTrue(VCard.parse(null).empty());
        assertTrue(VCard.parse("<html>not a card</html>").empty());
    }
}
