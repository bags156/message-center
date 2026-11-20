package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** A message shows at once while it's translated, goes once translated, and never jumps ahead of the ones after it. */
public class TranslatingOutboxTest {
    @Test public void waitsForItsTranslationThenGoesWithIt() {
        Outbox box = new Outbox(new MemoryPrefs());
        JSONObject o = box.addTranslating("c1", "On my way", "", "es", "Spanish", "", "en", 0);
        String id = o.optString("id");
        assertEquals("what you wrote shows meanwhile", "On my way", box.get(id).optString("text"));
        assertTrue(Outbox.label(box.get(id), true, "", 0).startsWith("Translating to Spanish"));
        assertNull("not sent while translating", box.next());
        assertTrue(box.translated(id, "Ya voy"));
        assertEquals("Ya voy", box.get(id).optString("text"));
        assertEquals("On my way", box.get(id).optString("wrote"));
        assertEquals(Outbox.WAITING, box.get(id).optString("state"));
        assertEquals(id, box.next().optString("id"));
    }

    @Test public void laterMessagesInTheSameChatWaitForIt() {
        Outbox box = new Outbox(new MemoryPrefs());
        String first = box.addTranslating("c1", "Running late", "", "es", "Spanish", "", "en", 0).optString("id");
        String after = box.add("c1", "👍", "").optString("id");
        String elsewhere = box.add("c2", "hi", "").optString("id");
        assertEquals("another chat isn't held up", elsewhere, box.next().optString("id"));
        box.remove(elsewhere);
        assertNull("the next one in the same chat waits its turn", box.next());
        box.translated(first, "Voy tarde");
        assertEquals(first, box.next().optString("id"));
        box.remove(first);
        assertEquals(after, box.next().optString("id"));
    }

    @Test public void takenBackOrFailedItNeverGoesUntranslated() {
        Outbox box = new Outbox(new MemoryPrefs());
        String id = box.addTranslating("c1", "See you at 7:30", "", "es", "Spanish", "", "en", 0).optString("id");
        box.remove(id);
        assertFalse("taken back: the translation has nowhere to go", box.translated(id, "Nos vemos a las 7:30"));
        String failed = box.addTranslating("c1", "See you at 7:30", "", "es", "Spanish", "", "en", 0).optString("id");
        box.translating(failed, Outbox.NOT_TRANSLATED, "It wasn't sent: the translation changed a time.");
        assertNull("it waits for you", box.next());
        assertTrue(Outbox.label(box.get(failed), true, "", 0).startsWith("Not translated"));
        assertFalse("only a translating one takes a translation", box.translated(failed, "x"));
        box.translating(failed, Outbox.TRANSLATING, "");
        assertEquals("translating again shows what you wrote", "See you at 7:30", box.get(failed).optString("text"));
        assertTrue(box.translated(failed, "See you at 7:30"));
        assertEquals(failed, box.next().optString("id"));
    }

    @Test public void itsUndoWindowStillCounts() {
        Outbox box = new Outbox(new MemoryPrefs());
        String id = box.addTranslating("c1", "Thanks!", "", "es", "Spanish", "", "en", 60_000).optString("id");
        box.translated(id, "¡Gracias!");
        assertEquals("translated within its undo window: held for the rest of it", Outbox.HOLD, box.get(id).optString("state"));
        assertNull(box.next());
    }
}
