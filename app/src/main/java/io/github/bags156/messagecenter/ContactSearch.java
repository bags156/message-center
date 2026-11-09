package io.github.bags156.messagecenter;

import java.text.Normalizer;
import java.util.Locale;

/** Matching what you type in "New conversation" against names and numbers. */
final class ContactSearch {
    /** Lowercase without accents, so "jose" finds "José". */
    static String fold(String s) {
        if (s == null || s.isEmpty()) return "";
        String plain = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * How well someone matches: 0 the name starts with what was typed, 1 a later word in it
     * does, 2 it's somewhere inside, 3 the number contains the typed digits (3 or more);
     * -1 no match. Names are given folded. A nickname counts like a name.
     */
    static int score(String name, String nickname, String numberDigits, String query, String queryDigits) {
        if (query.isEmpty()) return 0;
        int best = -1;
        for (String n : new String[]{name, nickname}) {
            if (n == null || n.isEmpty()) continue;
            int s = n.startsWith(query) ? 0 : wordStarts(n, query) ? 1 : n.contains(query) ? 2 : -1;
            if (s >= 0 && (best < 0 || s < best)) best = s;
        }
        if (best >= 0) return best;
        if (queryDigits.length() >= 3 && numberDigits != null && numberDigits.contains(queryDigits)) return 3;
        return -1;
    }

    private static boolean wordStarts(String name, String query) {
        for (int i = 1; i < name.length(); i++) {
            char before = name.charAt(i - 1);
            if (!Character.isLetterOrDigit(before) && name.startsWith(query, i)) return true;
        }
        return false;
    }

    private ContactSearch() {}
}
