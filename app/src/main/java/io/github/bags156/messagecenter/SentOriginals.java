package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/**
 * What you wrote, for messages that were translated before they went: the chat shows what was sent,
 * with "Sent in Spanish · Show what you wrote". Kept only on this phone (not in backups), found by
 * the chat and the sent words, and limited to the most recent few hundred.
 */
final class SentOriginals {
    private static final int KEEP = 400;
    private SentOriginals() {}

    static final class Original {
        final String text, from, to;
        Original(String text, String from, String to) { this.text = text; this.from = from; this.to = to; }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("sent-originals", Context.MODE_PRIVATE); }

    /** The same words, however the spacing at the ends came out. */
    static String key(String chat, String sent) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] h = d.digest((chat + "\u0000" + normalize(sent)).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format(Locale.ROOT, "%02x", h[i] & 255));
            return out.toString();
        } catch (Exception e) { return ""; }
    }
    static String normalize(String text) { return text == null ? "" : text.trim().replaceAll("\\s+", " "); }

    static void remember(Context c, String chat, String sent, String wrote, String from, String to) {
        String k = key(chat, sent);
        if (k.isEmpty() || normalize(sent).equals(normalize(wrote))) return;
        try {
            SharedPreferences p = prefs(c);
            SharedPreferences.Editor e = p.edit().putString(k, new JSONObject().put("w", wrote).put("f", from).put("t", to).put("at", System.currentTimeMillis()).toString());
            // Keep the newest few hundred.
            Map<String, ?> all = p.getAll();
            if (all.size() >= KEEP) {
                List<Map.Entry<String, ?>> old = new ArrayList<>(all.entrySet());
                old.sort((a, b) -> Long.compare(at(a.getValue()), at(b.getValue())));
                for (int i = 0; i <= all.size() - KEEP; i++) e.remove(old.get(i).getKey());
            }
            e.apply();
        } catch (Exception ignored) {}
    }

    static Original find(Context c, String chat, String sent) {
        if (sent == null || sent.trim().isEmpty()) return null;
        String raw = prefs(c).getString(key(chat, sent), null);
        if (raw == null) return null;
        try { JSONObject o = new JSONObject(raw); return new Original(o.optString("w"), o.optString("f"), o.optString("t")); } catch (Exception e) { return null; }
    }

    private static long at(Object raw) { try { return new JSONObject(String.valueOf(raw)).optLong("at"); } catch (Exception e) { return 0; } }
}
