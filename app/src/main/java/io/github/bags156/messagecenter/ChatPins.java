package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Messages pinned to the top of a chat (a gate code, an address, a Wi-Fi password). On this phone
 * only: Google's connection has no pins, so nobody else sees them. Each pin keeps the message's
 * text, so it shows even when the message is far back in the chat. Newest pin first.
 */
final class ChatPins {
    static final int MAX = 5;
    private final SharedPreferences prefs;

    ChatPins(Context context) { prefs = context.getSharedPreferences("pins", Context.MODE_PRIVATE); }

    synchronized List<JSONObject> list(String chat) {
        List<JSONObject> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs.getString(chat, "[]")); for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i)); } catch (Exception ignored) {}
        return out;
    }

    synchronized boolean has(String chat, String message) {
        for (JSONObject p : list(chat)) if (message.equals(p.optString("id"))) return true;
        return false;
    }

    /** Pins it first; true when that pushed the oldest pin out (there are at most MAX). */
    synchronized boolean pin(String chat, String message, String text, long at) {
        List<JSONObject> pins = list(chat);
        pins.removeIf(p -> message.equals(p.optString("id")));
        try { pins.add(0, new JSONObject().put("id", message).put("text", text).put("at", at).put("pinned", System.currentTimeMillis())); } catch (Exception ignored) {}
        boolean dropped = false;
        while (pins.size() > MAX) { pins.remove(pins.size() - 1); dropped = true; }
        save(chat, pins);
        return dropped;
    }

    synchronized void unpin(String chat, String message) {
        List<JSONObject> pins = list(chat);
        if (pins.removeIf(p -> message.equals(p.optString("id")))) save(chat, pins);
    }

    synchronized void clear(String chat) { prefs.edit().remove(chat).apply(); }

    private void save(String chat, List<JSONObject> pins) {
        if (pins.isEmpty()) { prefs.edit().remove(chat).apply(); return; }
        JSONArray a = new JSONArray(); for (JSONObject p : pins) a.put(p);
        prefs.edit().putString(chat, a.toString()).apply();
    }
}
