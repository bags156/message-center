package io.github.bags156.messagecenter;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative fallback-text parsing. Unmatched/ambiguous reactions remain readable. */
public final class ReactionParser {
    private static final Pattern PATTERN = Pattern.compile(
        "^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned|Removed a heart from|Removed a like from|Removed a dislike from|Removed a laugh from|Removed an emphasis from|Removed a question mark from) ([\\\"“])(.+)([\\\"”])$",
        Pattern.DOTALL);

    public static final class Reaction {
        public final String emoji, quote;
        public final boolean removed;
        Reaction(String emoji, String quote, boolean removed) {
            this.emoji = emoji; this.quote = quote; this.removed = removed;
        }
    }

    public static Reaction parse(String text) {
        if (text == null) return null;
        Matcher match = PATTERN.matcher(text);
        if (!match.matches()) return null;
        if ((match.group(2).equals("“") && !match.group(4).equals("”"))
                || (match.group(2).equals("\"") && !match.group(4).equals("\""))) return null;
        String verb = match.group(1);
        boolean removed = verb.startsWith("Removed ");
        String emoji;
        if (verb.equals("Loved") || verb.contains("heart")) emoji = "❤️";
        else if (verb.equals("Disliked") || verb.contains("dislike")) emoji = "👎";
        else if (verb.equals("Liked") || verb.contains("like")) emoji = "👍";
        else if (verb.equals("Laughed at") || verb.contains("laugh")) emoji = "😂";
        else if (verb.equals("Emphasized") || verb.contains("emphasis")) emoji = "‼️";
        else emoji = "❓";
        return new Reaction(emoji, match.group(3), removed);
    }

    /** Only one exact prior message is sufficient evidence to attach a reaction. */
    public static int uniqueMatch(String quote, List<String> candidates) {
        int found = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (quote.equals(candidates.get(i))) {
                if (found >= 0) return -1;
                found = i;
            }
        }
        return found;
    }
    private ReactionParser() {}
}
