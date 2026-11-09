package io.github.bags156.messagecenter;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message effects, like iPhone's. RCS has no way to send an effect, so an iPhone adds a note
 * instead: "Happy birthday! (sent with Balloons)". This finds that note, so the chat can play the
 * effect and show the message without it. A few phrases ("Happy birthday", "Congratulations")
 * play an effect on their own, as they do on iPhones.
 */
final class Effects {
    // Full-screen effects.
    static final String BALLOONS = "balloons", CONFETTI = "confetti", LOVE = "love", LASERS = "lasers",
        FIREWORKS = "fireworks", CELEBRATION = "celebration", SHOOTING_STAR = "shooting star",
        ECHO = "echo", SPOTLIGHT = "spotlight";
    // Effects on the message bubble itself.
    static final String SLAM = "slam", LOUD = "loud", GENTLE = "gentle", INK = "invisible ink";

    private static final String[] ALL = {BALLOONS, CONFETTI, LOVE, LASERS, FIREWORKS, CELEBRATION,
        SHOOTING_STAR, ECHO, SPOTLIGHT, SLAM, LOUD, GENTLE, INK};

    /** "(sent with Balloons)", "(Sent with Slam effect)", "(sent with Loud Effect)", at the end of the text. */
    private static final Pattern NOTE = Pattern.compile(
        "(?is)^(.*?)[ \\t]*\\n?[ \\t]*\\(\\s*sent with ([a-z][a-z ]{1,20}?)(?:\\s+effect)?\\s*\\)\\s*$");

    private static final Pattern BIRTHDAY = Pattern.compile("(?i)\\bhappy\\s+(?:birthday|b-?day)\\b");
    private static final Pattern CONGRATS = Pattern.compile("(?i)\\bcongrat(?:s|ulations)\\b");
    private static final Pattern LUNAR = Pattern.compile("(?i)\\bhappy\\s+(?:lunar|chinese)\\s+new\\s+year\\b");
    private static final Pattern NEW_YEAR = Pattern.compile("(?i)\\bhappy\\s+new\\s+year\\b");
    private static final Pattern PEW = Pattern.compile("(?i)\\bpew\\s+pew\\b");

    /** A message's text without an iPhone effect note, and the effect it named ("" if none). */
    static final class Note {
        final String text, effect;
        Note(String text, String effect) { this.text = text; this.effect = effect; }
    }

    static Note note(String text) {
        if (text == null) return new Note("", "");
        // Cheap check first: this runs for every message drawn.
        int open = text.lastIndexOf('(');
        if (open < 0 || !text.substring(open).toLowerCase(Locale.ROOT).contains("ent with")) return new Note(text, "");
        Matcher m = NOTE.matcher(text);
        if (!m.matches()) return new Note(text, "");
        String name = m.group(2).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        for (String e : ALL) if (e.equals(name)) return new Note(m.group(1).replaceAll("[ \\t\\n]+$", ""), e);
        return new Note(text, "");
    }

    /** The text without an effect note. */
    static String strip(String text) { return note(text).text; }

    /** The effect a phrase sets off on its own, or "". */
    static String phrase(String text) {
        if (text == null || text.isEmpty()) return "";
        if (BIRTHDAY.matcher(text).find()) return BALLOONS;
        if (CONGRATS.matcher(text).find()) return CONFETTI;
        if (LUNAR.matcher(text).find()) return CELEBRATION;
        if (NEW_YEAR.matcher(text).find()) return FIREWORKS;
        if (PEW.matcher(text).find()) return LASERS;
        return "";
    }

    /** True for effects that play over the whole chat; false for the bubble ones. */
    static boolean fullScreen(String effect) {
        return !effect.isEmpty() && !SLAM.equals(effect) && !LOUD.equals(effect) && !GENTLE.equals(effect) && !INK.equals(effect);
    }

    /** "Balloons", "Shooting Star", "Invisible Ink". */
    static String label(String effect) {
        StringBuilder b = new StringBuilder();
        for (String w : effect.split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    /** An inbox or widget preview: the note removed, and invisible ink kept hidden. */
    static String preview(String text) {
        Note n = note(text);
        if (INK.equals(n.effect)) return "Sent with invisible ink";
        return n.text.isEmpty() && !n.effect.isEmpty() ? "Sent with " + label(n.effect) : n.text;
    }

    private Effects() {}
}
