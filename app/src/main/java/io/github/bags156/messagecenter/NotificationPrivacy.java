package io.github.bags156.messagecenter;

import android.content.Context;

/**
 * What notifications show: who a message is from and what it says (the default), who it's
 * from only, or nothing but "New message". Set for all chats in Settings, and changed for one
 * chat in its Details (that chat's choice wins). Applies to message notifications, unread
 * reminders and message reminders. While the phone is locked (or its screen is off), a stricter
 * choice for the lock screen can apply; unlocking shows the rest.
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

    private static android.content.SharedPreferences chats(Context c) { return c.getSharedPreferences("notification_privacy", Context.MODE_PRIVATE); }
    /** One chat's own choice, or "" if it follows the setting for all chats. */
    static String own(Context c, String chat) {
        if (chat == null || chat.isEmpty()) return "";
        String m = chats(c).getString(chat, "");
        return ALL.equals(m) || NAME.equals(m) || NONE.equals(m) ? m : "";
    }
    /** Gives one chat its own choice; "" goes back to the setting for all chats. */
    static void setOwn(Context c, String chat, String mode) {
        if (mode == null || mode.isEmpty()) chats(c).edit().remove(chat).apply(); else chats(c).edit().putString(chat, mode).apply();
    }
    /** How many chats have their own choice. */
    static int ownCount(Context c) { return chats(c).getAll().size(); }
    /** What this chat's notifications show when unlocked: its own choice, or the one for all chats. */
    static String chosen(Context c, String chat) { String o = own(c, chat); return o.isEmpty() ? mode(c) : o; }
    /** What this chat's notifications show right now: while locked, no more than the lock screen choice allows. */
    static String mode(Context c, String chat) {
        String m = chosen(c, chat), lock = lockMode(c);
        return SAME.equals(lock) || !locked(c) ? m : stricter(m, lock);
    }

    static final String LOCK_KEY = "notification_lock", SAME = "same";
    /** What shows while the phone is locked: SAME as unlocked, NAME or NONE. */
    static String lockMode(Context c) {
        String m = c.getSharedPreferences("paired", Context.MODE_PRIVATE).getString(LOCK_KEY, SAME);
        return NAME.equals(m) || NONE.equals(m) ? m : SAME;
    }
    static void setLockMode(Context c, String mode) { c.getSharedPreferences("paired", Context.MODE_PRIVATE).edit().putString(LOCK_KEY, mode).apply(); }
    static String lockLabel(String mode) { return NAME.equals(mode) ? "Name only" : NONE.equals(mode) ? "Nothing" : "Same as unlocked"; }
    /** The one of the two that shows less. */
    static String stricter(String a, String b) { return rank(a) >= rank(b) ? a : b; }
    private static int rank(String m) { return NONE.equals(m) ? 2 : NAME.equals(m) ? 1 : 0; }
    /** Device checks only: treat the phone as locked (true) or unlocked (false) instead of asking it. */
    static Boolean lockedForChecks;
    /** Whether the phone is locked or its screen is off (the lock screen is what someone would see). */
    static boolean locked(Context c) {
        if (lockedForChecks != null) return lockedForChecks;
        try {
            android.os.PowerManager power = c.getSystemService(android.os.PowerManager.class);
            android.app.KeyguardManager keyguard = c.getSystemService(android.app.KeyguardManager.class);
            return (power != null && !power.isInteractive()) || (keyguard != null && keyguard.isKeyguardLocked());
        } catch (Exception e) { return false; }
    }
    static boolean showText(Context c, String chat) { return ALL.equals(mode(c, chat)); }
    static boolean showName(Context c, String chat) { return !NONE.equals(mode(c, chat)); }
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
