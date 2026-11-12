package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ContactPeopleTest {
    @Test public void eachPersonAndNumberOnce() {
        List<ContactPeople.Person> people = ContactPeople.group(Arrays.asList(
                new ContactPeople.Row(1, "Ben Smith", "4795550134", "+14795550134", "Mobile"),
                new ContactPeople.Row(1, "Ben Smith", "(479) 555-0134", "+14795550134", ""),      // Google's copy
                new ContactPeople.Row(1, "Ben Smith", "+1 479-555-0134", "+14795550134", "Mobile"), // another app's copy
                new ContactPeople.Row(1, "Ben Smith", "(479) 555-0199", "+14795550199", "Work"),
                new ContactPeople.Row(2, "Cara Jones", "(501) 555-0100", "+15015550100", "Mobile")));
        assertEquals(2, people.size());
        assertEquals("Ben Smith", people.get(0).name);
        assertEquals(2, people.get(0).numbers.size());
        assertEquals("(479) 555-0134", people.get(0).numbers.get(0).shown); // written out, not bare digits
        assertEquals("Mobile", people.get(0).numbers.get(0).label);
        assertEquals("Work", people.get(0).numbers.get(1).label);
    }

    @Test public void samePersonSavedTwiceIsOneEntry() {
        List<ContactPeople.Person> people = ContactPeople.group(Arrays.asList(
                new ContactPeople.Row(1, "Ben Smith", "(479) 555-0134", "+14795550134", "Mobile"),
                new ContactPeople.Row(7, "ben smith", "479-555-0134", "", "SIM"),
                new ContactPeople.Row(7, "ben smith", "(479) 555-0177", "", "Home")));
        assertEquals(1, people.size());
        assertEquals(2, people.get(0).numbers.size());
    }

    @Test public void differentPeopleWithTheSameNameStaySeparate() {
        List<ContactPeople.Person> people = ContactPeople.group(Arrays.asList(
                new ContactPeople.Row(1, "John Smith", "(479) 555-0134", "+14795550134", "Mobile"),
                new ContactPeople.Row(2, "John Smith", "(212) 555-0100", "+12125550100", "Mobile")));
        assertEquals(2, people.size());
    }

    @Test public void noNameUsesTheNumberAndBlankNumbersAreSkipped() {
        List<ContactPeople.Person> people = ContactPeople.group(Arrays.asList(
                new ContactPeople.Row(3, "", "(479) 555-0134", "+14795550134", ""),
                new ContactPeople.Row(4, "Nobody", "", "", "")));
        assertEquals(1, people.size());
        assertEquals("(479) 555-0134", people.get(0).name);
    }
}
