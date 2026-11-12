package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class WhoIsThisTest {
    static JSONObject person(String id, String number, String full) throws Exception {
        JSONObject p = new JSONObject().put("ID", new JSONObject().put("participantID", id).put("number", number));
        if (!full.isEmpty()) p.put("fullName", full);
        return p;
    }
    static JSONObject chat(String id, String name, JSONObject... people) throws Exception {
        JSONArray all = new JSONArray().put(new JSONObject().put("ID", new JSONObject().put("participantID", "me").put("number", "+15550000000")).put("isMe", true));
        for (JSONObject p : people) all.put(p);
        return new JSONObject().put("conversationID", id).put("name", name).put("participants", all);
    }

    @Test public void groupsInCommonAndTheirName() throws Exception {
        JSONObject dm = chat("dm", "", person("d", "+14795550134", ""));
        JSONObject soccer = chat("g1", "Soccer parents", person("a", "+15015550100", "Amy"), person("d", "(479) 555-0134", "Dana R."));
        JSONObject family = chat("g2", "Family", person("b", "+15015550101", "Bo"), person("c", "+15015550102", "Cy"));
        JSONObject work = chat("g3", "Work", person("x", "+15015550103", "Xi"), person("d2", "479-555-0134", ""));
        WhoIsThis.Found f = WhoIsThis.find("+14795550134", "dm", Arrays.asList(dm, soccer, family, work), null);
        assertEquals(2, f.groups.size());
        assertEquals("g1", f.groups.get(0).optString("conversationID"));
        assertEquals("g3", f.groups.get(1).optString("conversationID"));
        assertEquals("Dana R.", f.name);
    }

    @Test public void googleListNameWhenNoGroupHasOne() throws Exception {
        JSONArray google = new JSONArray().put(new JSONObject().put("name", "Dana Reed").put("number", new JSONObject().put("number", "+14795550134")));
        WhoIsThis.Found f = WhoIsThis.find("+14795550134", "dm", Collections.emptyList(), google);
        assertTrue(f.groups.isEmpty());
        assertEquals("Dana Reed", f.name);
        // A "name" that's only a number doesn't count.
        JSONArray numbersOnly = new JSONArray().put(new JSONObject().put("name", "(479) 555-0134").put("number", new JSONObject().put("number", "+14795550134")));
        assertEquals("", WhoIsThis.find("+14795550134", "dm", Collections.emptyList(), numbersOnly).name);
    }

    @Test public void shortCodesMatchNothing() throws Exception {
        JSONObject g = chat("g1", "Group", person("a", "12345", "Amy"), person("b", "+15015550100", "Bo"));
        assertTrue(WhoIsThis.find("12345", "dm", Collections.singletonList(g), null).groups.isEmpty());
    }

    @Test public void alsoInWording() {
        assertEquals("No group chats in common", WhoIsThis.alsoIn(Collections.emptyList()));
        assertEquals("Also in Soccer parents", WhoIsThis.alsoIn(Collections.singletonList("Soccer parents")));
        assertEquals("Also in Soccer parents and Family", WhoIsThis.alsoIn(Arrays.asList("Soccer parents", "Family")));
        assertEquals("Also in Soccer parents, Family and 2 more", WhoIsThis.alsoIn(Arrays.asList("Soccer parents", "Family", "Work", "Gym")));
    }
}
