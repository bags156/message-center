package io.github.bags156.messagecenter;

import android.content.Context;

/**
 * What notifications show: who a message is from and what it says (the default), who it's
 * from only, or nothing but "New message". Applies to message notifications, unread
 * reminders and message reminders.
 */
final class NotificationPrivacy {
    static final String KEY = "notification_content";
    static final String ALL = "all", NAME = "name", NONE = "none";
    private NotificationPrivacy() {}

    static String mode(Context c) {
        String m = c.getSharedPreferences("paired", Context.MODE_PRIVATE).getString(KEY, ALL);
        return NAME.equals(m) || NONE.equals(m) ? m : ALL;
    }
    static void set(Context c, String mode) { c.getSharedPreferences("paired", Context.MODE_PRIVATE).edit().putString(KEY, mode).apply(); }
    /** Whether notifications may show what a message says. */
    static boolean showText(Context c) { return ALL.equals(mode(c)); }
    /** Whether notifications may show who a message is from (and so whether bubbles are possible). */
    static boolean showName(Context c) { return !NONE.equals(mode(c)); }

    static String label(String mode) {
        switch (mode) {
            case NAME: return "Name only";
            case NONE: return "Nothing";
            default: return "Name and message";
        }
    }
    /** "New message" or "3 new messages". */
    static String count(int n) { return n <= 1 ? "New message" : n + " new messages"; }
}
