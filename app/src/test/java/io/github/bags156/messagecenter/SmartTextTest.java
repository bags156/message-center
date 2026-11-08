package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

public class SmartTextTest {
    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Chicago");
    /** Saturday, Nov 7 2026, 5:00 PM. */
    private static final long NOW = at(2026, Calendar.NOVEMBER, 7, 17, 0);
    private static long at(int y, int mo, int d, int h, int mi) { Calendar c = Calendar.getInstance(ZONE); c.clear(); c.set(y, mo, d, h, mi, 0); return c.getTimeInMillis(); }
    private static long when(String text) { List<SmartText.Hit> h = SmartText.dateTimes(text, NOW, ZONE); return h.isEmpty() ? -1 : h.get(0).time; }
    private static String kinds(String text) { StringBuilder b = new StringBuilder(); for (SmartText.Hit h : SmartText.all(text, NOW, ZONE)) b.append(h.kind).append(':').append(h.value).append(' '); return b.toString().trim(); }

    @Test public void trackingNumbers() {
        assertEquals("ups:1Z999AA10123456784", kinds("Your package 1Z999AA10123456784 shipped"));
        assertEquals("amazon:TBA123456789012", kinds("Track TBA123456789012"));
        assertEquals("usps:9400111899223197428490", kinds("USPS 9400111899223197428490 out for delivery"));
        assertEquals("fedex:123456789012", kinds("FedEx tracking 123456789012"));
        assertEquals("", kinds("Order 123456789012 confirmed"));
        assertEquals("https://www.ups.com/track?tracknum=1Z999AA10123456784", SmartText.trackingUrl(SmartText.tracking("1Z999AA10123456784").get(0)));
    }
    @Test public void flights() {
        assertEquals("flight:AA 1234", kinds("My flight is AA 1234, lands at XNA"));
        assertEquals("flight:DL 88", kinds("Boarding DL88 now"));
        assertEquals("", kinds("Address AR 72712"));
        assertEquals("", kinds("AA 1234 meeting notes"));
    }
    @Test public void datesAndTimes() {
        assertEquals(at(2026, Calendar.NOVEMBER, 13, 18, 0), when("Dinner Friday at 6?"));
        assertEquals(at(2026, Calendar.NOVEMBER, 8, 19, 30), when("tomorrow 7:30pm works"));
        assertEquals(at(2026, Calendar.NOVEMBER, 12, 15, 0), when("Appointment Nov 12 at 3pm"));
        assertEquals(at(2026, Calendar.NOVEMBER, 7, 18, 0), when("Let's do 6pm tonight"));
        assertEquals(at(2026, Calendar.DECEMBER, 20, 12, 0), when("Party 12/20 at noon"));
        assertEquals(at(2026, Calendar.NOVEMBER, 9, 9, 0), when("Monday at 9am"));
        assertEquals(at(2026, Calendar.NOVEMBER, 7, 20, 0), when("tonight at 8"));
        assertEquals(at(2027, Calendar.JANUARY, 3, 10, 15), when("Jan 3rd at 10:15 am"));
        assertEquals(-1, when("I have 2 dogs"));
        assertEquals(-1, when("Sun 5 times"));
        assertEquals(-1, when("at 6"));
        assertEquals(-1, when("Score 21 to 7"));
    }
    @Test public void sendersKeywordsAndSearch() {
        assertTrue(SmartText.shortCodeSender("28302"));
        assertTrue(SmartText.shortCodeSender("AMAZON"));
        assertFalse(SmartText.shortCodeSender("+14795551234"));
        assertFalse(SmartText.shortCodeSender("(479) 555-1234"));
        assertEquals("urgent", SmartText.keyword("This is URGENT please", Arrays.asList("urgent", "flight")));
        assertEquals("", SmartText.keyword("insurgents", Arrays.asList("urgent")));
        SmartText.Search s = SmartText.parseSearch("from:\"Hannah Kay\" has:photo pizza last:7d");
        assertEquals("pizza", s.text); assertEquals("Hannah Kay", s.from); assertTrue(s.photo); assertFalse(s.link); assertEquals(7, s.days);
    }
}
