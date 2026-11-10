package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * Settings Message Center keeps per conversation on this phone: pins, theme,
 * read receipts, and what has been seen here. Google's protocol has no pin action,
 * so a pin changed here overrides Google's pin state only inside this app.
 */
final class ChatSettings {
    long replyDismissed(String id){return prefs.getLong("reply-done:"+id,0);}
    long replySnoozed(String id){return prefs.getLong("reply-later:"+id,0);}
    void dismissReply(String id,long time){prefs.edit().putLong("reply-done:"+id,time).remove("reply-later:"+id).apply();}
    void snoozeReply(String id,long time){prefs.edit().putLong("reply-later:"+id,time).apply();}
    private final SharedPreferences prefs;
    ChatSettings(Context context) { prefs = context.getSharedPreferences("chat-settings", Context.MODE_PRIVATE); }

    boolean pinned(JSONObject chat) {
        String id = chat.optString("conversationID");
        return effectivePin(prefs.getString("pin:" + id, ""), chat.optBoolean("pinned"));
    }
    /** "1"/"0" is this app's override; anything else follows Google. */
    static boolean effectivePin(String override, boolean google) {
        if ("1".equals(override)) return true;
        if ("0".equals(override)) return false;
        return google;
    }
    void setPinned(String id, boolean pinned) { prefs.edit().putString("pin:" + id, pinned ? "1" : "0").apply(); }

    ChatTheme theme(String id) { return ChatTheme.of(prefs.getString("theme:" + id, "default")); }
    void setTheme(String id, String key) { prefs.edit().putString("theme:" + id, key).apply(); }

    /** Whether opening this chat marks it read on the phone (which sends RCS read receipts). Off unless turned on for this chat. */
    boolean receipts(String id) { return prefs.getBoolean("receipts:" + id, false); }
    void setReceipts(String id, boolean on) { prefs.edit().putBoolean("receipts:" + id, on).apply(); }

    long seen(String id) { return prefs.getLong("seen:" + id, 0); }
    void markSeen(String id, long time) { if (time > seen(id)) prefs.edit().putLong("seen:" + id, time).apply(); }
    String markedRead(String id) { return prefs.getString("read:" + id, ""); }
    void setMarkedRead(String id, String messageID) { prefs.edit().putString("read:" + id, messageID).apply(); }

    /** Unread in Google and not yet viewed here. */
    boolean unread(JSONObject chat) {
        String id = chat.optString("conversationID");
        return markedUnread(id) || (chat.optBoolean("unread") && seen(id) < PairedProtocol.time(chat));
    }

    /** "Mark as unread" is kept in this app only; Google's protocol has no way to set it on the phone. */
    boolean markedUnread(String id) { return prefs.getBoolean("unread:" + id, false); }
    void setMarkedUnread(String id, boolean unread) { prefs.edit().putBoolean("unread:" + id, unread).apply(); }

    /** Muted chats post no notifications here (Google Messages is told too). */
    boolean muted(String id) { return prefs.getBoolean("muted:" + id, false); }
    void setMuted(String id, boolean muted) { prefs.edit().putBoolean("muted:" + id, muted).apply(); }

    /** The phone's own RCS "Send read receipts" switch: 1 on, 0 off, -1 not reported yet. */
    int phoneReceipts() { return prefs.getInt("phone-receipts", -1); }
    void setPhoneReceipts(boolean on) { prefs.edit().putInt("phone-receipts", on ? 1 : 0).apply(); }
    int phoneRcs() { return prefs.getInt("phone-rcs",-1); }
    void setPhoneRcs(boolean on) { prefs.edit().putInt("phone-rcs",on?1:0).apply(); }
    int receiptAvailability() { return phoneReceipts()==0||phoneRcs()==0?0:phoneReceipts()==1&&phoneRcs()==1?1:-1; }

    /** Notifications are paused for this chat until this time (0 when not snoozed). */
    long snoozedUntil(String id) { return prefs.getLong("snooze:" + id, 0); }
    boolean snoozed(String id) { return snoozedUntil(id) > System.currentTimeMillis(); }
    void setSnooze(String id, long until) { if (until <= 0) prefs.edit().remove("snooze:" + id).apply(); else prefs.edit().putLong("snooze:" + id, until).apply(); }
    /** Chat background: "" (none), "stock:<name>", or "photo" (a picture you chose, saved by the app). */
    String background(String id) { return prefs.getString("background:" + id, ""); }
    void setBackground(String id, String value) { if (value == null || value.isEmpty()) prefs.edit().remove("background:" + id).apply(); else prefs.edit().putString("background:" + id, value).apply(); }
    void clear() { prefs.edit().clear().apply(); }
}
