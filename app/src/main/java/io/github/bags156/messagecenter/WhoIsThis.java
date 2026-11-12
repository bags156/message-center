package io.github.bags156.messagecenter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * For a number you haven't saved: the group chats you're both in, and a name Google has for them
 * (from a group's member list, or Google's own contact list). Plain Java for unit tests.
 */
final class WhoIsThis {
    private WhoIsThis() {}

    static final class Found {
        final List<JSONObject> groups = new ArrayList<>();
        String name = "";
    }

    /** chats in the order to list them (newest first); chatId is the chat you're in (left out). */
    static Found find(String number, String chatId, List<JSONObject> chats, JSONArray googleContacts) {
        Found out = new Found();
        String key = ContactSearch.key(number);
        if (key.length() < 7) return out; // short codes and fragments match too much
        for (JSONObject c : chats) {
            if (c.optString("conversationID").equals(chatId) || PairedProtocol.other(c) != null) continue;
            for (JSONObject p : PairedProtocol.members(c)) {
                if (!key.equals(ContactSearch.key(PairedProtocol.number(p)))) continue;
                if (!out.groups.contains(c)) out.groups.add(c);
                if (out.name.isEmpty()) out.name = realName(PairedProtocol.string(p, "fullName"), PairedProtocol.string(p, "firstName"));
            }
        }
        if (out.name.isEmpty() && googleContacts != null)
            for (int i = 0; i < googleContacts.length() && out.name.isEmpty(); i++) {
                JSONObject ct = googleContacts.optJSONObject(i); if (ct == null) continue;
                JSONObject num = ct.optJSONObject("number");
                if (num != null && key.equals(ContactSearch.key(PairedProtocol.string(num, "number")))) out.name = realName(ct.optString("name"), "");
            }
        return out;
    }

    /** "Also in Soccer parents", "… and Family", "…, Family and 2 more". */
    static String alsoIn(List<String> titles) {
        if (titles.isEmpty()) return "No group chats in common";
        if (titles.size() == 1) return "Also in " + titles.get(0);
        if (titles.size() == 2) return "Also in " + titles.get(0) + " and " + titles.get(1);
        return "Also in " + titles.get(0) + ", " + titles.get(1) + " and " + (titles.size() - 2) + " more";
    }

    private static String realName(String full, String first) {
        for (String n : new String[]{full, first}) if (n != null && !n.trim().isEmpty() && !ContactSearch.numberLike(n)) return n.trim();
        return "";
    }
}
