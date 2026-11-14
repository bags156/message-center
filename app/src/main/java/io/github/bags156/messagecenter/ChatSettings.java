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
    /** For unit tests: kept in the given storage. */
    ChatSettings(SharedPreferences prefs) { this.prefs = prefs; }

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

    /** A chat's colors: its own if you gave it some, otherwise the ones every chat uses. "" is that all-chats choice. */
    ChatTheme theme(String id) {
        String own = id == null || id.isEmpty() ? null : prefs.getString("theme:" + id, null);
        return ChatTheme.of(own != null ? own : allTheme());
    }
    /** The colors every chat uses unless it has its own (Settings → Appearance → Chat color). */
    String allTheme() { return prefs.getString("theme_all", "default"); }
    /** Whether this chat has colors of its own (rather than the all-chats ones). */
    boolean ownTheme(String id) { return id != null && !id.isEmpty() && prefs.contains("theme:" + id); }
    /** Gives a chat its own colors; "" sets the colors for all chats (those with their own keep them). */
    void setTheme(String id, String key) {
        if (id == null || id.isEmpty()) prefs.edit().putString("theme_all", key).apply();
        else prefs.edit().putString("theme:" + id, key).apply();
    }
    /**
     * The music app the person in this chat uses, as you set it: an app's name (MusicLinks.App),
     * "off" to leave the song links you send as they are, or "" to go by the links they send.
     */
    String theirMusic(String id) { return prefs.getString("music_their:" + id, ""); }
    void setTheirMusic(String id, String value) {
        if (value == null || value.isEmpty()) prefs.edit().remove("music_their:" + id).apply(); else prefs.edit().putString("music_their:" + id, value).apply();
    }
    /** The service of the last song link they sent ("" if none yet). */
    String learnedMusic(String id) { return prefs.getString("music_learned:" + id, ""); }
    void learnMusic(String id, String app) { if (!app.equals(learnedMusic(id))) prefs.edit().putString("music_learned:" + id, app).apply(); }
    /** Back to the colors every chat uses. */
    void sameAsAll(String id) { prefs.edit().remove("theme:" + id).apply(); }
    /** The chats that have colors of their own. */
    java.util.List<String> ownThemes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : prefs.getAll().keySet()) if (k.startsWith("theme:")) out.add(k.substring("theme:".length()));
        return out;
    }
    /** Colors for all chats; with ownToo, chats that had their own switch as well (any can still be changed after). */
    void themeAll(String key, boolean ownToo) {
        SharedPreferences.Editor e = prefs.edit().putString("theme_all", key);
        if (ownToo) for (String id : ownThemes()) e.remove("theme:" + id);
        e.apply();
    }

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
    void setSnooze(String id, long until) { if (until <= 0) prefs.edit().remove("snooze:" + id).remove("snooze-quiet:" + id).apply(); else prefs.edit().putLong("snooze:" + id, until).remove("snooze-quiet:" + id).apply(); }
    /** "Until it's quiet": the snooze ends once the chat has had no new messages for this long. */
    static final long QUIET_MS = 30 * 60_000L;
    boolean untilQuiet(String id) { return prefs.getBoolean("snooze-quiet:" + id, false) && snoozed(id); }
    void snoozeUntilQuiet(String id) { prefs.edit().putBoolean("snooze-quiet:" + id, true).putLong("snooze:" + id, System.currentTimeMillis() + QUIET_MS).apply(); }
    /** A message arrived: a chat snoozed until it's quiet stays snoozed for QUIET_MS from now. */
    void activity(String id) { if (untilQuiet(id)) prefs.edit().putLong("snooze:" + id, System.currentTimeMillis() + QUIET_MS).apply(); }
    /** Chat background: "" (none), "stock:<name>", or "photo" (a picture you chose, saved by the app). */
    String background(String id) { return prefs.getString("background:" + id, ""); }
    void setBackground(String id, String value) { if (value == null || value.isEmpty()) prefs.edit().remove("background:" + id).apply(); else prefs.edit().putString("background:" + id, value).apply(); }
    void clear() { prefs.edit().clear().apply(); }
}
