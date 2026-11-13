package io.github.bags156.messagecenter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PhotoPrefetchTest {
    private static final long NOW = 1_800_000_000_000L;

    private static JSONObject message(boolean mine, long agoMs, JSONObject media) throws Exception {
        return new JSONObject().put("messageID", "m1").put("conversationID", "c1").put("participantID", mine ? "me" : "p1")
            .put("timestamp", (NOW - agoMs) * 1000)
            .put("messageStatus", new JSONObject().put("status", mine ? "OUTGOING_COMPLETE" : "INCOMING_COMPLETE"))
            .put("messageInfo", new JSONArray().put(new JSONObject().put("actionMessageID", "p1").put("mediaContent", media)));
    }

    private static JSONObject photo(String mime, long size) throws Exception {
        return new JSONObject().put("mediaID", "x").put("mimeType", mime).put("mediaName", "a").put("size", size);
    }

    @Test public void aPhotoSomeoneJustSentIsFetched() throws Exception {
        JSONObject p = photo("image/jpeg", 2_000_000);
        assertTrue(PhotoPrefetch.wanted(message(false, 60_000, p), p, NOW, true, false));
        JSONObject gif = photo("image/gif", 900_000);
        assertTrue(PhotoPrefetch.wanted(message(false, 60_000, gif), gif, NOW, true, false));
    }

    @Test public void notYourOwnVideosBigOnesOldOnesOrWhenTheSettingsSayNo() throws Exception {
        JSONObject p = photo("image/jpeg", 2_000_000);
        assertFalse("your own photo is already here", PhotoPrefetch.wanted(message(true, 60_000, p), p, NOW, true, false));
        JSONObject video = photo("video/mp4", 2_000_000);
        assertFalse("videos wait for a tap", PhotoPrefetch.wanted(message(false, 60_000, video), video, NOW, true, false));
        JSONObject huge = photo("image/jpeg", 40L << 20);
        assertFalse("over 25 MB", PhotoPrefetch.wanted(message(false, 60_000, huge), huge, NOW, true, false));
        assertFalse("a week old", PhotoPrefetch.wanted(message(false, 7L * 24 * 3_600_000, p), p, NOW, true, false));
        assertFalse("full photos off", PhotoPrefetch.wanted(message(false, 60_000, p), p, NOW, false, false));
        assertFalse("Data Saver", PhotoPrefetch.wanted(message(false, 60_000, p), p, NOW, true, true));
    }
}
