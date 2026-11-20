package io.github.bags156.messagecenter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Send in a chat that translates what you send (with the stronger translator): the message shows in
 * the chat at once, with what you wrote, and is translated in the background (Outbox.TRANSLATING).
 * Once the translation's ready it replaces the text and goes, even if you've left the chat or the
 * app. Taken back (undo) or deleted meanwhile, the translation stops. If it can't be translated, it
 * waits for you instead of going untranslated (Outbox.NOT_TRANSLATED).
 */
final class OutgoingTranslation {
    private static final Set<String> running = ConcurrentHashMap.newKeySet();
    private static final Set<String> texts = ConcurrentHashMap.newKeySet();
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Whether this exact text is being translated to send (a background translation of the same draft carries on, and it uses that). */
    static boolean sending(String text) { return texts.contains(text); }

    /** Translates a TRANSLATING outbox text and lets it go. done (may be null) runs on the main thread afterwards. */
    static void start(Context context, String id, Runnable done) {
        Context app = context.getApplicationContext(); Outbox box = new Outbox(app);
        JSONObject o = box.get(id); if (o == null || !Outbox.TRANSLATING.equals(o.optString("state")) || !running.add(id)) return;
        String wrote = o.optString("wrote"), chat = o.optString("chat"), target = o.optString("to"), writing = o.optString("writing"), phone = o.optString("phone");
        texts.add(wrote);
        Translation.worker.urgent(() -> {
            BooleanSupplier gone = () -> { JSONObject now = box.get(id); return now == null || !Outbox.TRANSLATING.equals(now.optString("state")); };
            try {
                if (gone.getAsBoolean()) return;
                String detected = Translation.writtenIn(app, wrote), text = wrote;
                if (!TranslationPlan.alreadyIn(detected, target)) {
                    Translation.Result r = Translation.translate(app, wrote, TranslationPlan.outgoingSource(wrote, "auto".equals(writing) ? detected : "", phone, writing), target, true, step -> {}, gone);
                    if (!r.same && !TranslationPlan.unchanged(wrote, r.text)) { text = r.text; SentOriginals.remember(app, chat, text, wrote, r.from, r.to); }
                }
                if (box.translated(id, text)) Diagnostics.note("A message was translated, then sent");
            } catch (CancellationException ignored) {
            } catch (Exception e) {
                if (!gone.getAsBoolean()) box.translating(id, Outbox.NOT_TRANSLATED, "It wasn't sent: " + (e.getMessage() == null ? "the translation couldn't finish." : e.getMessage()));
            } finally {
                running.remove(id); texts.remove(wrote);
                main.post(() -> { PairedService.outboxChanged(); if (done != null) done.run(); });
            }
        });
    }

    /** After a restart (or coming back to the app): anything left translating carries on. */
    static void resume(Context context, Runnable done) {
        for (JSONObject o : new Outbox(context).all()) if (Outbox.TRANSLATING.equals(o.optString("state"))) start(context, o.optString("id"), done);
    }

    private OutgoingTranslation() {}
}
