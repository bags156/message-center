package io.github.bags156.messagecenter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Suggested replies for common English messages ("Thanks!" → "You're welcome!"), for phones whose
 * Android has no reply model of its own. Plain rules, on the phone; pure Java so tests run anywhere.
 * Only what fits almost any conversation: nothing that guesses facts (times, places, plans).
 */
final class ReplyRules {
    private ReplyRules() {}

    private static final class Rule {
        final Pattern when; final List<String> replies;
        Rule(String regex, String... replies) { this.when = Pattern.compile(regex); this.replies = Arrays.asList(replies); }
    }

    // First match wins, so the specific comes before the general.
    private static final List<Rule> RULES = Arrays.asList(
        new Rule("\\b(thank you|thanks|thx|tysm|ty)\\b", "You're welcome!", "Anytime!", "👍"),
        new Rule("\\b(happy birthday|hbd)\\b", "Thank you!", "Thanks so much!", "❤️"),
        new Rule("\\b(congrats|congratulations)\\b", "Thank you!", "Thanks so much!", "😊"),
        new Rule("\\b(love you|ily|love u)\\b", "Love you too!", "❤️", "Love you more"),
        new Rule("\\b(good ?night|night night|gn)\\b", "Good night!", "Sleep well", "Night!"),
        new Rule("^(good ?morning|morning)\\b", "Good morning!", "Morning!", "☀️"),
        new Rule("\\b(sorry|my bad|apologi[sz]e)\\b", "No worries!", "It's okay", "All good"),
        new Rule("\\b(where are you|where r u|where you at|wya)\\b", "On my way", "Almost there", "Just left"),
        new Rule("\\b(on my way|omw|leaving now|heading (over|out|there)|be there (soon|in))\\b", "👍", "See you soon!", "Drive safe"),
        new Rule("^(i'?m |im |we'?re )?(here|outside)\\b", "Coming!", "Be right out", "👍"),
        new Rule("\\b(call me|can you call|give me a call)\\b", "Calling now", "Can't talk right now", "In a bit"),
        new Rule("^(how are you|how r u|how's it going|hows it going|how are things|how you doing|how have you been|hru|wyd|what are you (up to|doing)|what's up|whats up|sup)\\b", "Good, you?", "Not much, you?", "Doing well!"),
        new Rule("\\b(want to|wanna|do you want|you want to|let's|lets|down to|are you free|you free|u free|are you around|can you (come|make it|join))\\b.*\\?\\s*$", "Sure!", "Sounds good", "Can't, sorry"),
        new Rule("^(are|is|do|does|did|can|could|will|would|should|have|has|was|were|r u|you|u)\\b.*\\?\\s*$", "Yes", "No", "Not sure"),
        new Rule("^(hi|hey|hello|hiya|yo|howdy|heyy+)\\b", "Hey!", "Hi!", "👋"),
        new Rule("^(ok|okay|k|kk|sounds good|cool|great|perfect|awesome|got it|will do)\\W*$", "👍", "Great!", "Thanks!")
    );

    /** Up to three replies to a message they sent, or none when nothing fits (or it isn't English). */
    static List<String> suggest(String message) {
        String m = message == null ? "" : message.trim().toLowerCase(Locale.ROOT).replace('’', '\'');
        if (m.isEmpty() || m.length() > 200 || !english(m)) return new ArrayList<>();
        for (Rule r : RULES) if (r.when.matcher(m).find()) return new ArrayList<>(r.replies);
        return new ArrayList<>();
    }

    /** Mostly plain Latin letters (emoji and punctuation aside): English words, or near enough to try. */
    private static boolean english(String m) {
        int letters = 0, plain = 0;
        for (int i = 0; i < m.length(); i++) { char c = m.charAt(i); if (Character.isLetter(c)) { letters++; if (c < 128) plain++; } }
        return letters > 0 && plain * 10 >= letters * 9 && !m.matches(".*[¿¡].*");
    }
}
