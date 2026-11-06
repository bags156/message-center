package io.github.bags156.messagecenter;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ConversationProjectorTest {
    private ConversationProjector.Message message(String text,String sender) {
        ConversationProjector.Message m=new ConversationProjector.Message();
        m.text=text; m.sender=sender; m.status="observed"; return m;
    }
    @Test public void attachesEmojiAndKeepsTheReactorIdentity() {
        ConversationProjector.Message original=message("Meet at noon","You");
        ConversationProjector.Message reaction=message("❤️ to “Meet at noon”","Morgan");
        List<ConversationProjector.Message> items=Arrays.asList(original,reaction);
        ConversationProjector.apply(items);
        assertEquals("❤️",original.reactions.get("Morgan"));
        assertTrue(reaction.hidden); assertFalse(original.contextOnly);
        assertEquals("❤️ to “Meet at noon”",reaction.sourceText);
    }
    @Test public void missingOriginalIsAnExplicitQuoteWithTappableReactorData() {
        ConversationProjector.Message reaction=message("👍 to “See you soon”","Taylor");
        ConversationProjector.apply(Arrays.asList(reaction));
        assertEquals("See you soon",reaction.text); assertTrue(reaction.contextOnly);
        assertTrue(reaction.contextNote.contains("wasn’t captured"));
        assertEquals("👍",reaction.reactions.get("Taylor")); assertFalse(reaction.hidden);
    }
    @Test public void neverAttachesToOneOfSeveralIdenticalMessages() {
        ConversationProjector.Message a=message("OK","You"),b=message("OK","You");
        ConversationProjector.Message reaction=message("❤️ to “OK”","Morgan");
        ConversationProjector.apply(Arrays.asList(a,b,reaction));
        assertTrue(a.reactions.isEmpty()); assertTrue(b.reactions.isEmpty());
        assertTrue(reaction.contextOnly); assertTrue(reaction.contextNote.contains("more than one"));
    }
    @Test public void twoPeopleCanReactToOneOriginal() {
        ConversationProjector.Message original=message("Confirmed","You");
        ConversationProjector.apply(Arrays.asList(original,message("❤️ to “Confirmed”","Morgan"),message("🎉 to “Confirmed”","Taylor")));
        assertEquals(2,original.reactions.size());
    }
    @Test public void removalDoesNotEraseANewerDifferentEmoji() {
        ConversationProjector.Message original=message("Confirmed","You");
        ConversationProjector.apply(Arrays.asList(original,message("❤️ to “Confirmed”","Morgan"),message("👍 to “Confirmed”","Morgan"),message("Removed ❤️ from “Confirmed”","Morgan")));
        assertEquals("👍",original.reactions.get("Morgan"));
    }
    @Test public void matchingRemovalClearsTheReaction() {
        ConversationProjector.Message original=message("Confirmed","You");
        ConversationProjector.apply(Arrays.asList(original,message("❤️ to “Confirmed”","Morgan"),message("Removed ❤ from “Confirmed”","Morgan")));
        assertTrue(original.reactions.isEmpty());
    }
    @Test public void reprocessingPreviouslySavedRecordsIsStable() {
        List<ConversationProjector.Message> items=new ArrayList<>();
        items.add(message("Liked “Thanks”","Morgan")); ConversationProjector.apply(items);
        assertTrue(items.get(0).contextOnly);
        items.add(0,message("Thanks","You")); ConversationProjector.apply(items);
        assertTrue(items.get(1).hidden); assertEquals("👍",items.get(0).reactions.get("Morgan"));
        ConversationProjector.apply(items);
        assertEquals(1,items.get(0).reactions.size());
    }
    @Test public void redactionIsNotTreatedAsRecoveredContent() {
        ConversationProjector.Message redacted=message("Sensitive notification content hidden","");
        ConversationProjector.apply(Arrays.asList(redacted));
        assertTrue(redacted.redacted); assertFalse(redacted.hidden); assertTrue(redacted.reactions.isEmpty());
    }
    @Test public void replyTextDoesNotInventAnOriginalMessage() {
        ConversationProjector.Message reply=message("Replied to a message: That works for me","Morgan");
        ConversationProjector.apply(Arrays.asList(reply));
        assertEquals("That works for me",reply.text);
        assertTrue(reply.contextNote.contains("not included"));
    }
}
