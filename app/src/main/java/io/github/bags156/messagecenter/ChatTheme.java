package io.github.bags156.messagecenter;

import java.util.Arrays;
import java.util.List;

/** Per-conversation colors for your bubbles and the send button. Pure Java for unit tests. */
final class ChatTheme {
    final String key, label;
    private final int bubbleDark, bubbleLight, accentDark, accentLight;

    private ChatTheme(String key, String label, int bubbleDark, int bubbleLight, int accentDark, int accentLight) {
        this.key = key; this.label = label;
        this.bubbleDark = bubbleDark; this.bubbleLight = bubbleLight;
        this.accentDark = accentDark; this.accentLight = accentLight;
    }

    static final List<ChatTheme> ALL = Arrays.asList(
        new ChatTheme("default", "Default", 0xff3b4766, 0xffd3e3fd, 0xffa8c7fa, 0xff0b57d0),
        new ChatTheme("ocean", "Ocean", 0xff1d5c63, 0xffc2ebe8, 0xff80d5d0, 0xff006a65),
        new ChatTheme("forest", "Forest", 0xff2d5733, 0xffcde8cd, 0xff9cd49e, 0xff2e6b30),
        new ChatTheme("sunset", "Sunset", 0xff7a3d1f, 0xffffdbc8, 0xffffb68e, 0xffa04100),
        new ChatTheme("berry", "Berry", 0xff5c2a5e, 0xfff4d3f3, 0xffe8b3e6, 0xff8a3f8b),
        new ChatTheme("rose", "Rose", 0xff6d2c3b, 0xffffd9df, 0xffffb1c0, 0xffa1344c),
        new ChatTheme("gold", "Gold", 0xff665120, 0xfffae5ad, 0xffe6c66d, 0xff7a5d00),
        new ChatTheme("graphite", "Graphite", 0xff45474d, 0xffe2e2e9, 0xffc4c6cf, 0xff44474e));

    static ChatTheme of(String key) {
        for (ChatTheme t : ALL) if (t.key.equals(key)) return t;
        return ALL.get(0);
    }

    int bubble(boolean dark) { return dark ? bubbleDark : bubbleLight; }
    int onBubble(boolean dark) { return dark ? 0xfff1f0f7 : 0xff1a1b20; }
    int accent(boolean dark) { return dark ? accentDark : accentLight; }
    int onAccent(boolean dark) { return dark ? 0xff102033 : 0xffffffff; }
}
