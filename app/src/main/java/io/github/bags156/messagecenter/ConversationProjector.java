package io.github.bags156.messagecenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presentation only: original notification records stay intact in storage. */
public final class ConversationProjector {
    public static class Message {
        public String id, sender, text, status;
        public long time;
        public boolean outgoing, hidden, contextOnly, redacted;
        public String sourceText, contextNote, reactionEvent;
        public final Map<String, String> reactions = new LinkedHashMap<>();
    }

    public static void apply(List<? extends Message> messages) {
        Map<String, List<Message>> originals = new HashMap<>();
        for (Message m : messages) {
            if (m.sourceText == null) m.sourceText = m.text;
            m.text = m.sourceText;
            m.hidden = false; m.contextOnly = false; m.contextNote = null;
            m.reactionEvent = null; m.reactions.clear();
            m.redacted = NotificationContent.isRedacted(m.text);
            if (m.redacted) continue;
            ReactionParser.Reaction reaction = ReactionParser.parse(m.text);
            if (reaction == null) {
                originals.computeIfAbsent(m.text, key -> new ArrayList<>()).add(m);
                String reply = NotificationContent.replyBody(m.text);
                if (reply != null) {
                    m.text = reply;
                    m.contextNote = "Reply · original message not included by Google Messages";
                }
                continue;
            }
            List<Message> matches = originals.get(reaction.quote);
            if (matches != null && matches.size() == 1) {
                Message original = matches.get(0);
                if (reaction.removed) {
                    if (ReactionParser.sameEmoji(original.reactions.get(m.sender), reaction.emoji))
                        original.reactions.remove(m.sender);
                } else original.reactions.put(m.sender, reaction.emoji);
                m.hidden = true;
            } else {
                // A quote in a reaction is evidence of quoted text, not recovered history.
                m.contextOnly = true; m.text = reaction.quote;
                m.contextNote = matches == null
                    ? "Quoted in a reaction · original message wasn’t captured"
                    : "Quoted in a reaction · more than one original matches";
                if (reaction.removed) m.reactionEvent = m.sender + " removed " + reaction.emoji;
                else m.reactions.put(m.sender, reaction.emoji);
            }
        }
    }
    private ConversationProjector() {}
}
