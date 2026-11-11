package io.github.bags156.messagecenter;

/**
 * A contact shared as plain text ("Ben Smith: (479) 555-0134"), which every phone can read and
 * turns into a tappable number, unlike a contact card file. Plain Java for unit tests.
 */
final class ContactText {
    private ContactText() {}

    /** "Name: number", plus ", email" when given. Just the number when there's no real name. */
    static String of(String name, String number, String email) {
        name = name == null ? "" : name.trim();
        number = number == null ? "" : number.trim();
        email = email == null ? "" : email.trim();
        // Contacts saved without a name show their number as the name.
        boolean named = !name.isEmpty() && !digits(name).equals(digits(number));
        StringBuilder out = new StringBuilder();
        if (named) out.append(name);
        if (!number.isEmpty()) out.append(out.length() > 0 ? ": " : "").append(number);
        if (!email.isEmpty()) out.append(out.length() > 0 ? (number.isEmpty() ? ": " : ", ") : "").append(email);
        return out.toString();
    }

    /** The message box text with snippet put in place of [start, end), on its own line when it's next to other words. */
    static Inserted insert(String text, int start, int end, String snippet) {
        if (text == null) text = "";
        start = Math.max(0, Math.min(start < 0 ? text.length() : start, text.length()));
        end = Math.max(start, Math.min(end < 0 ? start : end, text.length()));
        String before = text.substring(0, start), after = text.substring(end);
        String lead = before.isEmpty() || Character.isWhitespace(before.charAt(before.length() - 1)) ? "" : "\n";
        String trail = after.isEmpty() || Character.isWhitespace(after.charAt(0)) ? "" : "\n";
        return new Inserted(before + lead + snippet + trail + after, before.length() + lead.length() + snippet.length());
    }

    static final class Inserted {
        final String text; final int cursor;
        Inserted(String text, int cursor) { this.text = text; this.cursor = cursor; }
    }

    private static String digits(String s) {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) d.append(s.charAt(i));
        return d.toString();
    }
}
