package io.github.bags156.messagecenter;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ChatTextTest {
    @Test public void iphoneReplyFallbackIsRecognized() {
        assertEquals("Haha with his short hair he kinda does!", ChatText.replyFallbackBody("Replied to a message:\nHaha with his short hair he kinda does!"));
        assertEquals("Sure", ChatText.replyFallbackBody("Replied to a message: Sure"));
        assertNull(ChatText.replyFallbackBody("I replied to a message: earlier"));
        assertNull(ChatText.replyFallbackBody("Hello"));
    }

    @Test public void mediaReactionFallbacksBecomeOneLine() {
        assertEquals("Sam loved an image ❤️", ChatText.reactionLine("Sam", "Loved an image"));
        assertEquals("You laughed at a video 😂", ChatText.reactionLine("You", "Laughed at a video"));
        assertNull(ChatText.reactionLine("Sam", "Loved an image of the lake"));
        assertNull(ChatText.reactionLine("Sam", "He looks like CMC carrying the football"));
    }

    @Test public void quotedReactionFallbacksKeepTheQuote() {
        assertEquals("Keith reacted ❤️ to “Nice”", ChatText.reactionLine("Keith", "Loved “Nice”"));
        assertEquals("Keith removed 👍 from “Nice”", ChatText.reactionLine("Keith", "Removed a like from “Nice”"));
    }

    @Test public void numbersMatchOnLastTenDigits() {
        assertTrue(ChatText.sameNumber("+1 (555) 010-2000", "5550102000"));
        assertTrue(ChatText.sameNumber("+15550102000", "+1 555-010-2000"));
        assertFalse(ChatText.sameNumber("+15550102000", "+15550102001"));
        assertFalse(ChatText.sameNumber("", "5550102000"));
    }

    @Test public void typingLabelsReadNaturally() {
        assertEquals("", ChatText.typingLabel(Collections.emptyList()));
        assertEquals("Daniel is typing", ChatText.typingLabel(List.of("Daniel")));
        assertEquals("Daniel and Sam are typing", ChatText.typingLabel(List.of("Daniel", "Sam")));
        assertEquals("3 people are typing", ChatText.typingLabel(List.of("A", "B", "C")));
    }

    @Test public void reactionSummaryCountsEveryone() {
        assertEquals("❤️", ChatText.reactionSummary(Arrays.asList(new String[][]{{"❤️", "p1"}})));
        assertEquals("❤️👍 3", ChatText.reactionSummary(Arrays.asList(new String[][]{{"❤️", "p1"}, {"👍", "p2"}, {"❤️", "p3"}})));
    }

    @Test public void reactorsListEveryPersonAndEmoji() throws Exception {
        JSONObject m = new JSONObject("{reactions:[{data:{unicode:'❤️'},participantIDs:['p1','p2']},{data:{type:'LIKE'},participantIDs:['p1']},{data:{unicode:''},participantIDs:['p3']}]}");
        List<String[]> r = PairedProtocol.reactors(m);
        assertEquals(3, r.size());
        assertArrayEquals(new String[]{"❤️", "p2"}, r.get(1));
        assertArrayEquals(new String[]{"👍", "p1"}, r.get(2));
    }

    @Test public void typingNamesSkipYourselfAndUseFirstNames() throws Exception {
        JSONObject chat = new JSONObject("{isGroupChat:true,participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},"
            + "{ID:{participantID:'p1',number:'+15550102000'},fullName:'Daniel Mehus'},{ID:{participantID:'p2',number:'+15550103000'},fullName:'Sam Richard Belcher'}]}");
        assertEquals(List.of("Daniel", "Sam"), PairedProtocol.typingNames(chat, List.of("5550102000", "+1 555 010 3000", "+15550000000")));
        assertEquals(2, PairedProtocol.members(chat).size());
        assertTrue(PairedProtocol.isMe(chat, "me"));
        assertFalse(PairedProtocol.isMe(chat, "p1"));
    }

    @Test public void attachmentsAreListedAndLabelled() throws Exception {
        JSONObject m = new JSONObject("{messageInfo:[{mediaContent:{mediaID:'m1',mimeType:'image/heic',mediaName:'IMG_0221.heic',size:2400000}},"
            + "{mediaContent:{mediaID:'m2',mediaName:'clip.MOV'}},{mediaContent:{mediaID:'m3',mimeType:'application/pdf',mediaName:'Lease.pdf'}},{messageContent:{content:'Look'}}]}");
        assertEquals(3, PairedProtocol.media(m).size());
        assertEquals("Look", PairedProtocol.text(m));
        assertEquals("Look\n📷 Photo\n🎥 Video\n📎 Lease.pdf", PairedProtocol.body(m));
        assertEquals("image", PairedProtocol.mediaKind(PairedProtocol.media(m).get(0)));
        assertEquals("video", PairedProtocol.mediaKind(PairedProtocol.media(m).get(1)));
        assertEquals("file", PairedProtocol.mediaKind(PairedProtocol.media(m).get(2)));
        assertEquals("audio", PairedProtocol.mediaKind(new JSONObject("{mimeType:'audio/amr'}")));
        assertEquals("📎 File", PairedProtocol.mediaLabel(new JSONObject("{mimeType:'application/zip'}")));
    }

    @Test public void sizesReadNaturally() {
        assertEquals("", PairedProtocol.size(0));
        assertEquals("900 B", PairedProtocol.size(900));
        assertEquals("2 KB", PairedProtocol.size(2048));
        assertEquals("2.3 MB", PairedProtocol.size(2400000));
    }

    @Test public void typedNumbersBecomeInternational() {
        assertEquals("+14795550100", ChatText.normalizeNumber("(479) 555-0100"));
        assertEquals("+14795550100", ChatText.normalizeNumber("1 479 555 0100"));
        assertEquals("+447700900123", ChatText.normalizeNumber("+44 7700 900123"));
        assertEquals("", ChatText.normalizeNumber("Daniel"));
        assertEquals("", ChatText.normalizeNumber("555-0100"));
        assertEquals("", ChatText.normalizeNumber("+12"));
    }

    @Test public void firstLinkIsFoundAndCleaned() {
        assertEquals("https://example.com/a?b=1", ChatText.firstUrl("see https://example.com/a?b=1."));
        assertEquals("https://www.example.org", ChatText.firstUrl("(www.example.org)"));
        assertEquals("http://x.io/p", ChatText.firstUrl("http://x.io/p and https://y.io"));
        assertEquals("", ChatText.firstUrl("no links here, just example dot com"));
        assertEquals("example.com", ChatText.domain("https://www.Example.com:443/path?q#f"));
    }

    @Test public void openGraphTagsAreReadInAnyAttributeOrder() {
        java.util.Map<String, String> m = ChatText.metaTags("<head><meta content=\"Lake &amp; Trail\" property=\"og:title\">"
            + "<META name='description' content='A <b>quiet</b> walk'/><meta property=\"og:title\" content=\"second\"></head>");
        assertEquals("Lake & Trail", m.get("og:title"));
        assertEquals("A quiet walk", m.get("description"));
    }

    @Test public void oneTimeCodesAreFoundOnlyInCodeMessages() {
        assertEquals("482913", ChatText.oneTimeCode("Your verification code is 482913. Don't share it."));
        assertEquals("382915", ChatText.oneTimeCode("G-382915 is your Google verification code."));
        assertEquals("123456", ChatText.oneTimeCode("123 456 is your login code"));
        assertEquals("4821", ChatText.oneTimeCode("Use PIN 4821 to sign in"));
        assertEquals("", ChatText.oneTimeCode("Call me at 5550102000 about the code"));
        assertEquals("", ChatText.oneTimeCode("Your code total is $1234.50"));
        assertEquals("", ChatText.oneTimeCode("See you at 7:30, room 1204"));
        assertEquals("", ChatText.oneTimeCode(null));
    }

    @Test public void emojiOnlyMessagesAreCounted() {
        assertEquals(1, ChatText.emojiOnly("😂"));
        assertEquals(1, ChatText.emojiOnly("❤️"));
        assertEquals(1, ChatText.emojiOnly("👍🏽"));
        assertEquals(1, ChatText.emojiOnly("👨\u200D👩\u200D👧"));
        assertEquals(1, ChatText.emojiOnly("🇺🇸"));
        assertEquals(3, ChatText.emojiOnly(" 😂😂 😂 "));
        assertEquals(0, ChatText.emojiOnly("😂😂😂😂"));
        assertEquals(0, ChatText.emojiOnly("lol 😂"));
        assertEquals(0, ChatText.emojiOnly("1"));
        assertEquals(1, ChatText.emojiOnly("1\uFE0F\u20E3"));
    }

    @Test public void mentionsNeedAnAtSignAndAWholeName() {
        java.util.List<String> me = java.util.Arrays.asList("Will", "Will Bagley");
        assertTrue(ChatText.mentions("@Will can you grab ice?", me));
        assertTrue(ChatText.mentions("thanks @will!", me));
        assertTrue(ChatText.mentions("cc @Will Bagley", me));
        assertFalse(ChatText.mentions("Will you be there?", me));
        assertFalse(ChatText.mentions("@Willow is coming", me));
        assertFalse(ChatText.mentions("email bob@will.com", me));
    }

    @Test public void locationsAndGroupEventsAreRead() throws Exception {
        JSONObject place = new JSONObject("{messageID:'l',messageInfo:[{actionMessageID:'a1',locationContent:{messageContent:{content:'Main Street Park https://maps.google.com/?q=36.37,-94.21'}}}]}");
        assertEquals("Main Street Park", PairedProtocol.placeLabel(PairedProtocol.location(place).optString("text")));
        assertEquals("\uD83D\uDCCD Main Street Park", PairedProtocol.body(place));
        JSONObject chat = new JSONObject("{isGroupChat:true,participants:[{ID:{participantID:'p1'},fullName:'Sam Example'},{ID:{participantID:'p2'},fullName:'Dana Example'}]}");
        assertEquals("Sam Example left", PairedProtocol.event(new JSONObject("{participantID:'p1',messageStatus:{status:'TOMBSTONE_PARTICIPANT_LEFT'}}"), chat));
        assertEquals("Group renamed to \u201cTrip\u201d", PairedProtocol.event(new JSONObject("{participantID:'p2',messageStatus:{status:'TOMBSTONE_GROUP_RENAMED_GLOBAL'},messageInfo:[{messageContent:{content:'Trip'}}]}"), chat));
        assertEquals("", PairedProtocol.event(new JSONObject("{messageStatus:{status:'TOMBSTONE_SHOW_LINK_PREVIEWS'}}"), chat));
        assertEquals("", PairedProtocol.event(new JSONObject("{messageStatus:{status:'INCOMING_COMPLETE'}}"), chat));
    }

    @Test public void previewTitlesSkipBotPagesAndStoreNames() {
        assertEquals("Stanley Quencher 40 oz Tumbler", ChatText.previewTitle("Amazon.com: Stanley Quencher 40 oz Tumbler : Home & Kitchen", "", "https://www.amazon.com/dp/B0CJZMP7L1"));
        assertEquals("", ChatText.previewTitle("Amazon.com", "", "https://www.amazon.com/dp/B0CJZMP7L1"));
        assertEquals("Stanley Quencher H2 0 Tumbler", ChatText.previewTitle("Amazon.com", "", "https://www.amazon.com/Stanley-Quencher-H2-0-Tumbler/dp/B0CJZMP7L1?ref_=x"));
        assertEquals("", ChatText.previewTitle("Robot Check", "", "https://www.amazon.com/dp/B0CJZMP7L1"));
        assertEquals("", ChatText.previewTitle("Just a moment...", "", "https://example.com/post"));
        assertEquals("", ChatText.previewTitle("example.com", "", "https://example.com/post"));
        assertEquals("How to brew coffee", ChatText.previewTitle("How to brew coffee", "Example", "https://example.com/post"));
    }
}
