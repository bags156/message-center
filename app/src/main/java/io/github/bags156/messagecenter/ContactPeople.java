package io.github.bags156.messagecenter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phone contacts grouped into people for ⊕ → Contact: each person once, each of their numbers
 * once. Android lists every saved copy of a number (Google account, SIM, other apps) as its own
 * row, often in different formats, which made the same person appear several times. Plain Java
 * for unit tests.
 */
final class ContactPeople {
    private ContactPeople() {}

    /** One phone number row as the contacts provider has it. */
    static final class Row {
        final long contact; final String name, raw, normalized, label;
        Row(long contact, String name, String raw, String normalized, String label) {
            this.contact = contact; this.name = name == null ? "" : name.trim(); this.raw = raw == null ? "" : raw.trim();
            this.normalized = normalized == null ? "" : normalized.trim(); this.label = label == null ? "" : label.trim();
        }
    }

    static final class Number {
        final String shown, label, key;
        Number(String shown, String label, String key) { this.shown = shown; this.label = label; this.key = key; }
    }

    static final class Person {
        final long contact; final String name; final List<Number> numbers = new ArrayList<>(); final String folded;
        Person(long contact, String name) { this.contact = contact; this.name = name; this.folded = ContactSearch.fold(name); }
        boolean has(String key) { for (Number n : numbers) if (n.key.equals(key)) return true; return false; }
    }

    /** Rows in display order → people in that order, each number once (a formatted copy preferred over bare digits). */
    static List<Person> group(List<Row> rows) {
        Map<Long, Person> byContact = new LinkedHashMap<>();
        for (Row r : rows) {
            String key = ContactSearch.key(r.normalized.isEmpty() ? r.raw : r.normalized);
            if (key.isEmpty()) continue;
            Person p = byContact.get(r.contact);
            if (p == null) { p = new Person(r.contact, r.name.isEmpty() ? r.raw : r.name); byContact.put(r.contact, p); }
            add(p, r.raw, r.label, key);
        }
        // The same person saved twice without being linked (same name, a number in common): one entry.
        List<Person> out = new ArrayList<>();
        for (Person p : byContact.values()) {
            Person same = null;
            for (Person q : out) if (q.folded.equals(p.folded) && shares(q, p)) { same = q; break; }
            if (same == null) { out.add(p); continue; }
            for (Number n : p.numbers) add(same, n.shown, n.label, n.key);
        }
        return out;
    }

    private static void add(Person p, String shown, String label, String key) {
        for (int i = 0; i < p.numbers.size(); i++) {
            Number n = p.numbers.get(i);
            if (!n.key.equals(key)) continue;
            // Keep the written-out copy and any label.
            boolean better = !formatted(n.shown) && formatted(shown);
            if (better || n.label.isEmpty() && !label.isEmpty()) p.numbers.set(i, new Number(better ? shown : n.shown, n.label.isEmpty() ? label : n.label, key));
            return;
        }
        p.numbers.add(new Number(shown, label, key));
    }

    private static boolean shares(Person a, Person b) { for (Number n : b.numbers) if (a.has(n.key)) return true; return false; }

    private static boolean formatted(String number) { return number.matches(".*[\\s()\\-.].*"); }
}
