package io.github.bags156.messagecenter;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative fallback-text parsing. Unmatched/ambiguous reactions remain readable. */
public final class ReactionParser {
    private static final Pattern PATTERN = Pattern.compile(
        "^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned|Removed a heart from|Removed a like from|Removed a dislike from|Removed a laugh from|Removed an emphasis from|Removed a question mark from) ([\\\"“])(.+)([\\\"”])$",
        Pattern.DOTALL);
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
        "^(?:(Reacted) )?(.+?) (to|from) ([\\\"“‘])(.+)([\\\"”’])$", Pattern.DOTALL);
    private static final Pattern REMOVED_EMOJI_PATTERN = Pattern.compile(
        "^Removed (.+?) from ([\\\"“‘])(.+)([\\\"”’])$", Pattern.DOTALL);

    public static final class Reaction {
        public final String emoji, quote;
        public final boolean removed;
        Reaction(String emoji, String quote, boolean removed) {
            this.emoji = emoji; this.quote = quote; this.removed = removed;
        }
    }

    public static Reaction parse(String text) {
        if (text == null) return null;
        text = text.trim();
        Matcher removedEmoji = REMOVED_EMOJI_PATTERN.matcher(text);
        if (removedEmoji.matches() && isEmoji(removedEmoji.group(1))
                && quotesMatch(removedEmoji.group(2), removedEmoji.group(4))) {
            return new Reaction(removedEmoji.group(1), removedEmoji.group(3), true);
        }
        Matcher emojiMatch = EMOJI_PATTERN.matcher(text);
        if (emojiMatch.matches() && emojiMatch.group(3).equals("to") && isEmoji(emojiMatch.group(2))
                && quotesMatch(emojiMatch.group(4), emojiMatch.group(6))) {
            return new Reaction(emojiMatch.group(2), emojiMatch.group(5), false);
        }
        Matcher match = PATTERN.matcher(text);
        if (!match.matches()) return null;
        if (!quotesMatch(match.group(2), match.group(4))) return null;
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

    private static boolean quotesMatch(String open, String close) {
        return (open.equals("\"") && close.equals("\""))
            || (open.equals("“") && close.equals("”"))
            || (open.equals("‘") && close.equals("’"));
    }

    /** Accept emoji sequences, not ordinary words that happen to precede 'to'. */
    static boolean isEmoji(String value) {
        int[] points = value.codePoints().toArray();
        if (points.length == 0 || points.length > 64) return false;
        boolean base = false;
        for (int cp : points) {
            if (cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D || cp == 0x20E3
                    || (cp >= 0x1F3FB && cp <= 0x1F3FF)
                    || (cp >= 0xE0020 && cp <= 0xE007F)) continue;
            if ((cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF)
                    || (cp >= 0x2300 && cp <= 0x23FF) || cp == 0x203C || cp == 0x2049
                    || cp == 0x2122 || cp == 0x2139 || cp == 0x3030 || cp == 0x303D
                    || cp == 0x3297 || cp == 0x3299 || cp == 0xA9 || cp == 0xAE) {
                base = true; continue;
            }
            if ((cp >= '0' && cp <= '9' || cp == '#' || cp == '*') && value.contains("\u20e3")) {
                base = true; continue;
            }
            return false;
        }
        return base;
    }

    public static boolean sameEmoji(String first, String second) {
        return first != null && second != null
            && first.replace("\uFE0F", "").replace("\uFE0E", "")
                .equals(second.replace("\uFE0F", "").replace("\uFE0E", ""));
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
