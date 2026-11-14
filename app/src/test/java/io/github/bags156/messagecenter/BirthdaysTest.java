package io.github.bags156.messagecenter;

import static org.junit.Assert.*;

import java.time.LocalDate;
import org.junit.Test;

public class BirthdaysTest {
    @Test public void readsTheWaysContactsSaveBirthdays() {
        Birthdays.Birthday b = Birthdays.parse("1996-11-14");
        assertEquals(1996, b.year); assertEquals(11, b.month); assertEquals(14, b.day);
        b = Birthdays.parse("--07-21"); assertEquals(0, b.year); assertEquals(7, b.month); assertEquals(21, b.day);
        assertEquals("a placeholder year means no year", 0, Birthdays.parse("1604-07-21").year);
        assertEquals(1994, Birthdays.parse("19940721").year);
        assertEquals(21, Birthdays.parse("07/21/1994").day);
        assertNull(Birthdays.parse("sometime in July"));
        assertNull(Birthdays.parse("1994-13-40"));
    }

    @Test public void countsTheDaysToTheNextBirthday() {
        LocalDate today = LocalDate.of(2026, 11, 14);
        assertEquals(0, Birthdays.daysUntil(Birthdays.parse("--11-14"), today));
        assertEquals(1, Birthdays.daysUntil(Birthdays.parse("--11-15"), today));
        assertEquals("yesterday's is next year", 364, Birthdays.daysUntil(Birthdays.parse("--11-13"), today));
        assertEquals("a leap-day birthday is Feb 28 in other years", 1, Birthdays.daysUntil(Birthdays.parse("2000-02-29"), LocalDate.of(2027, 2, 27)));
    }

    @Test public void saysItTheWayYoudSayIt() {
        LocalDate saturday = LocalDate.of(2026, 11, 14);
        assertEquals("Hannah turns 30 today", Birthdays.label("Hannah", Birthdays.parse("1996-11-14"), saturday));
        assertEquals("Hannah's birthday is today", Birthdays.label("Hannah", Birthdays.parse("--11-14"), saturday));
        assertEquals("Hannah turns 31 tomorrow", Birthdays.label("Hannah", Birthdays.parse("1995-11-15"), saturday));
        assertEquals("Hannah's birthday is tomorrow", Birthdays.label("Hannah", Birthdays.parse("--11-15"), saturday));
        assertEquals("Hannah's birthday is Friday", Birthdays.label("Hannah", Birthdays.parse("--11-20"), saturday));
        assertNull("more than a week out says nothing", Birthdays.label("Hannah", Birthdays.parse("--11-21"), saturday));
    }
}
