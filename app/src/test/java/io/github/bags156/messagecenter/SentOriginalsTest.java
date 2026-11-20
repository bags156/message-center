package io.github.bags156.messagecenter;

import static org.junit.Assert.*;

import org.junit.Test;

public class SentOriginalsTest {
    /** The same sent words in the same chat find what you wrote, however the spacing came out; another chat or other words don't. */
    @Test public void keyIgnoresSpacingButNotChatOrWords() {
        String k = SentOriginals.key("chat-1", "Hola, ¿cómo estás?");
        assertEquals(k, SentOriginals.key("chat-1", "  Hola,  ¿cómo estás?\n"));
        assertNotEquals(k, SentOriginals.key("chat-2", "Hola, ¿cómo estás?"));
        assertNotEquals(k, SentOriginals.key("chat-1", "Hola, ¿cómo están?"));
        assertEquals(24, k.length());
    }
}
