package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names you give people on this phone only. They replace the contact's name in the chat list,
 * chats, notifications, and the widget. Keyed by phone number (last 10 digits).
 */
final class Nicknames {
    private Nicknames() {}
    private static final Map<String, String> names = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("nicknames", Context.MODE_PRIVATE); }

    static void load(Context context) {
        if (loaded) return;
        synchronized (Nicknames.class) {
            if (loaded) return;
            for (Map.Entry<String, ?> e : prefs(context).getAll().entrySet()) if (e.getValue() instanceof String) names.put(e.getKey(), (String) e.getValue());
            loaded = true;
        }
    }

    static String key(String number) {
        String d = ChatText.digits(number == null ? "" : number);
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    /** The nickname for this number, or "". */
    static String get(String number) {
        String k = key(number);
        if (k.isEmpty()) return "";
        String n = names.get(k);
        return n == null ? "" : n;
    }

    static void set(Context context, String number, String nickname) {
        String k = key(number); if (k.isEmpty()) return;
        String n = nickname == null ? "" : nickname.trim();
        if (n.isEmpty()) { names.remove(k); prefs(context).edit().remove(k).apply(); }
        else { names.put(k, n); prefs(context).edit().putString(k, n).apply(); }
    }
}
