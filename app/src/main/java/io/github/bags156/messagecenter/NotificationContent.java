package io.github.bags156.messagecenter;

import java.util.Locale;

/** Describes missing source information without claiming to recover it. */
public final class NotificationContent {
    public static boolean isRedacted(String text) {
        if (text == null) return false;
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("sensitive notification content hidden")
            || normalized.equals("sensitive content hidden")
            || normalized.equals("notification content hidden");
    }
    public static String title(String title, String latestText) {
        if (title != null && !title.trim().isEmpty()) return title;
        return isRedacted(latestText) ? "Content hidden by Android" : "Conversation";
    }
    public static String preview(String text) {
        if (isRedacted(text)) return "Android withheld this notification’s contents";
        ReactionParser.Reaction reaction = ReactionParser.parse(text);
        if (reaction != null) return reaction.removed ? "Reaction removed " + reaction.emoji : "New reaction " + reaction.emoji;
        String reply = replyBody(text);
        return reply == null ? text : "Reply: " + reply;
    }
    public static String replyBody(String text) {
        String prefix = "Replied to a message: ";
        return text != null && text.startsWith(prefix) && text.length() > prefix.length()
            ? text.substring(prefix.length()) : null;
    }
    private NotificationContent() {}
}
