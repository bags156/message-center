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
}
