package io.github.bags156.messagecenter;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** iPhone reactions that arrive as text are placed on the message they're about. */
public class TapbackTest {
    private static ChatText.Said text(String who, String text) { return new ChatText.Said(who, text, false, false, false, false); }
    private static ChatText.Said photo(String who) { return new ChatText.Said(who, "", true, false, false, false); }
    private static ChatText.Said video(String who) { return new ChatText.Said(who, "", false, true, false, false); }

    private static int target(List<ChatText.Said> said, int at) {
        boolean[] skip = new boolean[said.size()];
        for (int i = 0; i < said.size(); i++) skip[i] = ChatText.tapback(said.get(i).text) != null;
        return ChatText.tapbackTarget(ChatText.tapback(said.get(at).text), at, said, skip);
    }

    @Test public void parsesEveryShape() {
        ChatText.Tapback t = ChatText.tapback("Laughed at an image");
        assertEquals("😂", t.emoji); assertEquals("an image", t.object); assertNull(t.quote); assertFalse(t.removed);
        t = ChatText.tapback("Removed a laugh from an image");
        assertEquals("😂", t.emoji); assertEquals("an image", t.object); assertTrue(t.removed);
        t = ChatText.tapback("Reacted 🎉 to a photo");
        assertEquals("🎉", t.emoji); assertEquals("a photo", t.object); assertFalse(t.removed);
        t = ChatText.tapback("Removed 🎉 from a video");
        assertEquals("🎉", t.emoji); assertEquals("a video", t.object); assertTrue(t.removed);
        t = ChatText.tapback("Loved “Yay!!!”");
        assertEquals("❤️", t.emoji); assertEquals("Yay!!!", t.quote); assertNull(t.object);
        assertNull(ChatText.tapback("Laughed at an image of the dog"));
        assertNull(ChatText.tapback("Reacted thanks to a photo"));
        assertNull(ChatText.tapback("Yay!!!"));
    }

    @Test public void laughAtAnImageLandsOnThePhotoAbove() {
        // Shaped like the screenshot: Mom's photo, a few messages, then Hannah's reaction.
        List<ChatText.Said> said = new ArrayList<>();
        said.add(photo("mom"));
        said.add(text("mom", "Back in the USA!!"));
        said.add(text("me", "Woohoo! Welcome home!!"));
        said.add(text("hannah", "Laughed at an image"));
        said.add(text("hannah", "Yay!!!"));
        assertEquals(0, target(said, 3));
    }

    @Test public void prefersSomeoneElsesPhotoButFallsBackToTheirOwn() {
        List<ChatText.Said> said = new ArrayList<>();
        said.add(photo("mom"));
        said.add(photo("hannah"));
        said.add(text("hannah", "Loved an image"));
        assertEquals(0, target(said, 2));
        said.clear();
        said.add(photo("hannah"));
        said.add(text("hannah", "Loved an image"));
        assertEquals(0, target(said, 1));
    }

    @Test public void kindsMustMatch() {
        List<ChatText.Said> said = new ArrayList<>();
        said.add(video("mom"));
        said.add(photo("katie"));
        said.add(text("hannah", "Laughed at a video"));
        assertEquals(0, target(said, 2));
        said.clear();
        said.add(text("mom", "No pictures here"));
        said.add(text("hannah", "Loved an image"));
        assertEquals(-1, target(said, 1));
    }

    @Test public void quotedTextFindsTheLatestMatch() {
        List<ChatText.Said> said = new ArrayList<>();
        said.add(text("katie", "Yes"));
        said.add(text("mom", "Yes"));
        said.add(text("katie", "Something else"));
        said.add(text("hannah", "Liked “Yes”"));
        assertEquals(1, target(said, 3));
    }

    @Test public void shortenedQuotesAndSpacingStillMatch() {
        List<ChatText.Said> said = new ArrayList<>();
        said.add(text("mom", "Not quite home, but in Philly.\nWhich is home of a sorts"));
        said.add(text("hannah", "Laughed at “Not quite home, but in Philly. Which is…”"));
        said.add(text("katie", "Emphasized “Not quite home, but in Philly. Which is home of a sorts”"));
        assertEquals(0, target(said, 1));
        assertEquals(0, target(said, 2));
    }

    @Test public void neverLandsOnAnotherReactionOrALaterMessage() {
        List<ChatText.Said> said = new ArrayList<>();
        said.add(text("katie", "Loved “Hi”"));
        said.add(text("hannah", "Liked “Loved “Hi””"));
        said.add(text("mom", "Hi"));
        assertEquals(-1, target(said, 1));
        assertEquals(-1, target(said, 0));
    }

    @Test public void reactionLineStillDescribesUnplacedOnes() {
        assertEquals("Hannah reacted 🎉 to a photo", ChatText.reactionLine("Hannah", "Reacted 🎉 to a photo"));
        assertEquals("Hannah removed 😂 from an image", ChatText.reactionLine("Hannah", "Removed a laugh from an image"));
        assertEquals("Hannah laughed at an image 😂", ChatText.reactionLine("Hannah", "Laughed at an image"));
    }
}
