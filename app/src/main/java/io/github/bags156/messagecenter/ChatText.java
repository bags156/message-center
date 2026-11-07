package io.github.bags156.messagecenter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presentation rules for message text. Pure Java so they can be unit tested. */
final class ChatText {
    private ChatText() {}

    private static final Pattern REPLY_FALLBACK = Pattern.compile("^Replied to a message:[ \\t]*\\R?(.*)$", Pattern.DOTALL);
    private static final Pattern MEDIA_REACTION = Pattern.compile(
        "^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned) (an image|a photo|a video|a movie|an attachment|an audio message|a sticker|a GIF|a link)$");

    /** iPhones send a reply to a non-iPhone chat as "Replied to a message:" plus the text, with no link to the original. */
    static String replyFallbackBody(String body) {
        if (body == null) return null;
        Matcher m = REPLY_FALLBACK.matcher(body.trim());
        return m.matches() ? m.group(1).trim() : null;
    }

    /** "Loved an image" style iPhone fallbacks: {emoji, verb, object}, or null. */
    static String[] mediaReaction(String body) {
        if (body == null) return null;
        Matcher m = MEDIA_REACTION.matcher(body.trim());
        if (!m.matches()) return null;
        return new String[]{emoji(m.group(1)), m.group(1).toLowerCase(Locale.ROOT), m.group(2)};
    }

    static String emoji(String verb) {
        switch (verb) {
            case "Loved": return "❤️";
            case "Liked": return "👍";
            case "Disliked": return "👎";
            case "Laughed at": return "😂";
            case "Emphasized": return "‼️";
            default: return "❓";
        }
    }

    /**
     * A one-line description for reaction text an iPhone sent as a regular message, or null
     * when the text is an ordinary message. The quoted text is kept; nothing is matched to
     * another message by its wording.
     */
    static String reactionLine(String sender, String body) {
        String[] media = mediaReaction(body);
        if (media != null) return sender + " " + media[1] + " " + media[2] + " " + media[0];
        ReactionParser.Reaction r = ReactionParser.parse(body);
        if (r == null) return null;
        String quote = r.quote.replaceAll("\\s+", " ").trim();
        if (quote.length() > 60) quote = quote.substring(0, 59) + "…";
        return sender + (r.removed ? " removed " + r.emoji + " from “" : " reacted " + r.emoji + " to “") + quote + "”";
    }

    /** Phone numbers compared on their last ten digits, so "+1 (555) 010-2000" matches "5550102000". */
    static boolean sameNumber(String a, String b) {
        String x = digits(a), y = digits(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        int n = Math.min(10, Math.min(x.length(), y.length()));
        return x.substring(x.length() - n).equals(y.substring(y.length() - n));
    }

    static String digits(String value) { return value == null ? "" : value.replaceAll("\\D", ""); }

    /** "Daniel is typing", "Daniel and Sam are typing", "3 people are typing". */
    static String typingLabel(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0) + " is typing";
        if (names.size() == 2) return names.get(0) + " and " + names.get(1) + " are typing";
        return names.size() + " people are typing";
    }

    /** Up to three distinct emoji and the total count, e.g. "❤️👍 3". */
    static String reactionSummary(List<String[]> reactors) {
        StringBuilder emoji = new StringBuilder(); java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String[] r : reactors) if (seen.add(r[0]) && seen.size() <= 3) emoji.append(r[0]);
        return reactors.size() > 1 ? emoji + " " + reactors.size() : emoji.toString();
    }

    /** First name for compact labels; falls back to the whole name. */
    static String firstName(String name) {
        if (name == null) return "";
        String t = name.trim(); int space = t.indexOf(' ');
        return space > 0 && !t.startsWith("+") ? t.substring(0, space) : t;
    }
}
