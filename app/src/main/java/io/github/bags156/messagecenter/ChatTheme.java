package io.github.bags156.messagecenter;

import java.util.Arrays;
import java.util.List;

/** Per-conversation colors for your bubbles and the send button. Pure Java for unit tests. */
final class ChatTheme {
    final String key, label;
    private final int bubbleDark, bubbleLight, accentDark, accentLight;
    private final int onBubbleDark, onBubbleLight, onAccentDark, onAccentLight;

    private ChatTheme(String key, String label, int bubbleDark, int bubbleLight, int accentDark, int accentLight) {
        this(key, label, bubbleDark, bubbleLight, accentDark, accentLight, LIGHT_TEXT, DARK_TEXT, 0xff102033, 0xffffffff);
    }

    private ChatTheme(String key, String label, int bubbleDark, int bubbleLight, int accentDark, int accentLight,
                      int onBubbleDark, int onBubbleLight, int onAccentDark, int onAccentLight) {
        this.key = key; this.label = label;
        this.bubbleDark = bubbleDark; this.bubbleLight = bubbleLight;
        this.accentDark = accentDark; this.accentLight = accentLight;
        this.onBubbleDark = onBubbleDark; this.onBubbleLight = onBubbleLight;
        this.onAccentDark = onAccentDark; this.onAccentLight = onAccentLight;
    }

    static final int LIGHT_TEXT = 0xfff1f0f7, DARK_TEXT = 0xff1a1b20;
    /** The chat backgrounds a send button has to stand out from: dark (black is darker still) and light. */
    static final int DARK_BACKGROUND = 0xff111318, LIGHT_BACKGROUND = 0xfff8f9fc;
    static final String CUSTOM = "custom:";

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
        if (key != null && key.startsWith(CUSTOM)) {
            int color = customColor(key);
            if (color != 0) return custom(color, key.endsWith(":d"));
        }
        for (ChatTheme t : ALL) if (t.key.equals(key)) return t;
        return ALL.get(0);
    }

    /**
     * A theme from one color you picked. Your bubbles are exactly that color in the kind of theme you
     * picked it in (dark, which includes black, or light) and the same color at a matching depth in the
     * other. The words on them are light or dark, whichever reads better, and the send button is the
     * same color made just light or dark enough to stand out on the chat's background.
     */
    static ChatTheme custom(int color, boolean pickedInDark) {
        int rgb = color | 0xff000000;
        int other = withLightness(rgb, pickedInDark ? 0.88f : 0.30f);
        int bubbleDark = pickedInDark ? rgb : other, bubbleLight = pickedInDark ? other : rgb;
        int accentDark = standOut(rgb, DARK_BACKGROUND, true), accentLight = standOut(rgb, LIGHT_BACKGROUND, false);
        String key = CUSTOM + String.format("%06x", rgb & 0xffffff) + (pickedInDark ? ":d" : ":l");
        return new ChatTheme(key, "Custom", bubbleDark, bubbleLight, accentDark, accentLight,
            wordsOn(bubbleDark), wordsOn(bubbleLight),
            readable(accentDark, 0xffffffff, 0xff102033), readable(accentLight, 0xffffffff, 0xff102033));
    }

    /** The color a custom theme was made from (opaque), or 0 when the key isn't a custom one. */
    static int customColor(String key) {
        if (key == null || !key.startsWith(CUSTOM) || key.length() < CUSTOM.length() + 6) return 0;
        try { return 0xff000000 | Integer.parseInt(key.substring(CUSTOM.length(), CUSTOM.length() + 6), 16); }
        catch (NumberFormatException e) { return 0; }
    }

    boolean custom() { return key.startsWith(CUSTOM); }

    int bubble(boolean dark) { return dark ? bubbleDark : bubbleLight; }
    int onBubble(boolean dark) { return dark ? onBubbleDark : onBubbleLight; }
    int accent(boolean dark) { return dark ? accentDark : accentLight; }
    int onAccent(boolean dark) { return dark ? onAccentDark : onAccentLight; }

    // ---- Color arithmetic (WCAG contrast, HSL), without Android, for the unit tests ----

    /**
     * Light words on a bubble whenever they read well (3:1), else dark: on bright, saturated colors
     * (pink, red, blue) light words look right and read fine, even where dark ones measure a hair higher.
     */
    static int wordsOn(int bubble) { return contrast(LIGHT_TEXT, bubble) >= 3.0 ? LIGHT_TEXT : DARK_TEXT; }

    /** Whichever of two text colors reads better on a background. */
    static int readable(int background, int light, int dark) {
        return contrast(light, background) >= contrast(dark, background) ? light : dark;
    }

    /** The color, made lighter (on a dark background) or darker (on a light one) until it stands out at 3:1. */
    static int standOut(int color, int background, boolean darkBackground) {
        float[] hsl = hsl(color);
        int c = color | 0xff000000;
        for (int i = 0; i < 60 && contrast(c, background) < 3.0; i++) {
            hsl[2] = Math.max(0f, Math.min(1f, hsl[2] + (darkBackground ? 0.02f : -0.02f)));
            c = fromHsl(hsl[0], hsl[1], hsl[2]);
        }
        return c;
    }

    static int withLightness(int color, float lightness) { float[] hsl = hsl(color); return fromHsl(hsl[0], hsl[1], lightness); }

    /** WCAG contrast ratio between two colors, 1 to 21. */
    static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static double luminance(int color) {
        return 0.2126 * channel((color >> 16) & 0xff) + 0.7152 * channel((color >> 8) & 0xff) + 0.0722 * channel(color & 0xff);
    }

    private static double channel(int v) { double c = v / 255.0; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }

    /** Hue (0-360), saturation and lightness (0-1). */
    static float[] hsl(int color) {
        float r = ((color >> 16) & 0xff) / 255f, g = ((color >> 8) & 0xff) / 255f, b = (color & 0xff) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), l = (max + min) / 2f, d = max - min;
        float h = 0f, s = 0f;
        if (d > 0f) {
            s = d / (1f - Math.abs(2f * l - 1f));
            if (max == r) h = 60f * (((g - b) / d) % 6f);
            else if (max == g) h = 60f * ((b - r) / d + 2f);
            else h = 60f * ((r - g) / d + 4f);
            if (h < 0f) h += 360f;
        }
        return new float[]{h, Math.min(1f, s), l};
    }

    static int fromHsl(float h, float s, float l) {
        float c = (1f - Math.abs(2f * l - 1f)) * s, x = c * (1f - Math.abs((h / 60f) % 2f - 1f)), m = l - c / 2f;
        float r, g, b;
        int sector = (int) Math.floor(((h % 360f) + 360f) % 360f / 60f);
        switch (sector) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return 0xff000000 | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }
}
