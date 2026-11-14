package io.github.bags156.messagecenter;

import android.app.Person;
import android.content.Context;
import android.view.textclassifier.ConversationAction;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Suggested replies ("Sounds good!", "On my way") from Android's own text classifier: part of the
 * phone, on the phone, the same one that suggests replies in notifications. Nothing is sent
 * anywhere, and no library is added (the app stays fully open source). Some phones' Android has no
 * reply model; then there are simply no suggestions. Call off the main thread.
 */
final class SmartReplies {
    private SmartReplies() {}

    /** Up to three replies to the newest messages (oldest first), only when the newest is theirs. */
    static List<String> suggest(Context context, List<JSONObject> recent, JSONObject chat) {
        List<String> out = new ArrayList<>();
        try {
            if (recent.isEmpty() || PairedProtocol.outgoing(recent.get(recent.size() - 1), chat)) return out;
            List<ConversationActions.Message> said = new ArrayList<>();
            for (JSONObject m : recent.subList(Math.max(0, recent.size() - 10), recent.size())) {
                String text = PairedProtocol.text(m);
                if (text.trim().isEmpty()) continue;
                Person who = PairedProtocol.outgoing(m, chat) ? ConversationActions.Message.PERSON_USER_SELF
                    : new Person.Builder().setKey("p:" + m.optString("participantID")).setName(m.optString("participantID")).build();
                said.add(new ConversationActions.Message.Builder(who).setText(text)
                    .setReferenceTime(ZonedDateTime.ofInstant(Instant.ofEpochMilli(PairedProtocol.time(m)), ZoneId.systemDefault())).build());
            }
            if (said.isEmpty()) return out;
            ConversationActions.Request request = new ConversationActions.Request.Builder(said)
                .setMaxSuggestions(3)
                .setHints(Collections.singletonList(ConversationActions.Request.HINT_FOR_IN_APP))
                .setTypeConfig(new TextClassifier.EntityConfig.Builder().includeTypesFromTextClassifier(false)
                    .setIncludedTypes(Collections.singletonList(ConversationAction.TYPE_TEXT_REPLY)).build())
                .build();
            TextClassifier classifier = context.getSystemService(TextClassificationManager.class).getTextClassifier();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (ConversationAction a : classifier.suggestConversationActions(request).getConversationActions()) {
                CharSequence reply = a.getTextReply();
                if (ConversationAction.TYPE_TEXT_REPLY.equals(a.getType()) && reply != null && reply.toString().trim().length() > 0) seen.add(reply.toString().trim());
            }
            out.addAll(seen);
        } catch (Exception e) {
            // No classifier, or it can't (an unsupported language): no suggestions.
        }
        return out.size() > 3 ? out.subList(0, 3) : out;
    }
}
