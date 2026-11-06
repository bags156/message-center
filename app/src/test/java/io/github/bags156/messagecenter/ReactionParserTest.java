package io.github.bags156.messagecenter;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public class ReactionParserTest {
    @Test public void mapsHeartToQuotedMessage() {
        ReactionParser.Reaction r = ReactionParser.parse("Loved “See you at seven”");
        assertNotNull(r); assertEquals("❤️", r.emoji);
        assertEquals("See you at seven", r.quote); assertFalse(r.removed);
    }
    @Test public void doesNotMixLikeAndDislike() {
        assertEquals("👎", ReactionParser.parse("Disliked \"No\"").emoji);
        assertEquals("👍", ReactionParser.parse("Liked \"Yes\"").emoji);
    }
    @Test public void supportsReactionRemoval() {
        ReactionParser.Reaction r = ReactionParser.parse("Removed a heart from “Hi”");
        assertTrue(r.removed); assertEquals("❤️", r.emoji);
    }
    @Test public void preservesUnrecognizedAndMalformedMessages() {
        assertNull(ReactionParser.parse("I loved that message"));
        assertNull(ReactionParser.parse("Loved “Hello\""));
        assertNull(ReactionParser.parse("Loved an image"));
        assertNull(ReactionParser.parse("Replied to a message"));
    }
    @Test public void refusesDuplicateOrTruncatedTargets() {
        assertEquals(-1, ReactionParser.uniqueMatch("OK", Arrays.asList("OK", "OK")));
        assertEquals(-1, ReactionParser.uniqueMatch("Long…", Arrays.asList("Long message")));
        assertEquals(1, ReactionParser.uniqueMatch("Yes", Arrays.asList("No", "Yes")));
    }
    @Test public void parsesGoogleMessagesEmojiToQuoteFormat() {
        ReactionParser.Reaction r=ReactionParser.parse("❤️ to “All set for tomorrow!”");
        assertNotNull(r); assertEquals("❤️",r.emoji); assertEquals("All set for tomorrow!",r.quote);
    }
    @Test public void supportsArbitraryEmojiAndJoinedSequences() {
        assertEquals("👍🏽",ReactionParser.parse("👍🏽 to \"Sounds good\"").emoji);
        assertEquals("👨‍👩‍👧",ReactionParser.parse("Reacted 👨‍👩‍👧 to “Family day”").emoji);
        assertEquals("🎉",ReactionParser.parse("🎉 to ‘Congratulations’").emoji);
    }
    @Test public void parsesRemovedEmojiWithoutRemovingAnotherReaction() {
        ReactionParser.Reaction r=ReactionParser.parse("Removed ❤️ from “Hello”");
        assertTrue(r.removed); assertEquals("Hello",r.quote);
        assertTrue(ReactionParser.sameEmoji("❤","❤️"));
        assertFalse(ReactionParser.sameEmoji("👍","❤️"));
    }
    @Test public void rejectsOrdinaryWordsAndInvalidEmojiSequences() {
        assertNull(ReactionParser.parse("Hello to “everyone”"));
        assertNull(ReactionParser.parse("Reacted thanks to “everyone”"));
        assertNull(ReactionParser.parse("❤️ from “someone”"));
        assertNull(ReactionParser.parse("❤️ to “Hello\""));
    }
}
