package io.github.bags156.messagecenter;

import static org.junit.Assert.*;
import org.junit.Test;

public class NotificationContentTest {
    @Test public void explainsRedactedRowsWithoutDroppingThem() {
        assertEquals("Content hidden by Android",NotificationContent.title("","Sensitive notification content hidden"));
        assertEquals("Android withheld this notification’s contents",NotificationContent.preview("Sensitive notification content hidden"));
        assertFalse(NotificationContent.isRedacted("The notification says sensitive content hidden sometimes"));
    }
    @Test public void reactionPreviewDoesNotRepeatTheQuotedMessage() {
        assertEquals("New reaction ❤️",NotificationContent.preview("❤️ to “A generic example”"));
        assertEquals("Reaction removed ❤️",NotificationContent.preview("Removed ❤️ from “A generic example”"));
    }
    @Test public void ordinaryMessagesKeepTheirOriginalText() {
        assertEquals("Hello there",NotificationContent.preview("Hello there"));
        assertEquals("Conversation",NotificationContent.title(null,"Hello there"));
    }
}
