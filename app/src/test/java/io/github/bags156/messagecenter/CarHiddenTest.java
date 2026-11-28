package io.github.bags156.messagecenter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Chats you choose to keep off Android Auto. */
public class CarHiddenTest {
    @Test public void aChatCanBeKeptOffTheCar() {
        ChatSettings s = new ChatSettings(new MemoryPrefs());
        assertFalse("every chat shows in the car unless you say otherwise", s.hiddenFromCar("c1"));
        s.setHiddenFromCar("c1", true);
        assertTrue(s.hiddenFromCar("c1"));
        assertFalse("only that chat", s.hiddenFromCar("c2"));
        s.setHiddenFromCar("c1", false);
        assertFalse(s.hiddenFromCar("c1"));
    }
}
