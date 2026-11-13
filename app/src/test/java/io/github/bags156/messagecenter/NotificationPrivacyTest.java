package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NotificationPrivacyTest {
    @Test public void theLockScreenNeverShowsMore() {
        assertEquals(NotificationPrivacy.NAME, NotificationPrivacy.stricter(NotificationPrivacy.ALL, NotificationPrivacy.NAME));
        assertEquals(NotificationPrivacy.NONE, NotificationPrivacy.stricter(NotificationPrivacy.NAME, NotificationPrivacy.NONE));
        assertEquals(NotificationPrivacy.NONE, NotificationPrivacy.stricter(NotificationPrivacy.NONE, NotificationPrivacy.NAME));
        assertEquals(NotificationPrivacy.ALL, NotificationPrivacy.stricter(NotificationPrivacy.ALL, NotificationPrivacy.ALL));
        assertEquals("Same as unlocked", NotificationPrivacy.lockLabel(NotificationPrivacy.SAME));
        assertEquals("Name only", NotificationPrivacy.lockLabel(NotificationPrivacy.NAME));
        assertEquals("Nothing", NotificationPrivacy.lockLabel(NotificationPrivacy.NONE));
    }
}
