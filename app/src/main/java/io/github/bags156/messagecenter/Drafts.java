package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Unsent text per conversation, kept on disk so the bubble and the full app share one draft
 * (type in one, carry on in the other) and it survives the app closing.
 */
final class Drafts {
    static final String FILE = "drafts";
    private final SharedPreferences prefs;

    Drafts(Context context) { prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    String getOrDefault(String chat, String fallback) { return chat == null || chat.isEmpty() ? fallback : prefs.getString(chat, fallback); }

    void put(String chat, String text) {
        if (chat == null || chat.isEmpty()) return;
        String old = prefs.getString(chat, "");
        if (text == null || text.isEmpty()) { if (!old.isEmpty()) prefs.edit().remove(chat).apply(); }
        else if (!text.equals(old)) prefs.edit().putString(chat, text).apply();
    }

    void remove(String chat) { if (chat != null && !chat.isEmpty()) prefs.edit().remove(chat).apply(); }

    SharedPreferences prefs() { return prefs; }
}
