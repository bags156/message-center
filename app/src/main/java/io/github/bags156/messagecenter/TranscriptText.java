package io.github.bags156.messagecenter;

import java.util.List;
import java.util.regex.Pattern;

/** Joins the speech recognizer's pieces of a voice message into readable text. Pure Java for tests. */
final class TranscriptText {
    private static final Pattern PUNCTUATION = Pattern.compile("[.,?!]");
    private static final Pattern LONE_I = Pattern.compile("\\bi\\b(?!\\.\\w)");

    /**
     * When the recognizer added punctuation, its pieces are joined as they are. When it didn't
     * (some phones' recognizers can't), each piece, which it splits at pauses, becomes a
     * sentence: capitalized and ended with a period. In English, "i" becomes "I".
     */
    static String join(List<String> pieces, boolean english) {
        boolean formatted = false;
        for (String p : pieces) if (p != null && PUNCTUATION.matcher(p).find()) { formatted = true; break; }
        StringBuilder b = new StringBuilder();
        for (String raw : pieces) {
            if (raw == null) continue;
            String s = raw.trim().replaceAll("\\s+", " ");
            if (s.isEmpty()) continue;
            if (english) s = LONE_I.matcher(s).replaceAll("I");
            if (!formatted) {
                s = s.substring(0, 1).toUpperCase() + s.substring(1);
                char last = s.charAt(s.length() - 1);
                if (Character.isLetterOrDigit(last)) s += ".";
            }
            if (b.length() > 0) b.append(' ');
            b.append(s);
        }
        return b.toString();
    }

    private TranscriptText() {}
}
