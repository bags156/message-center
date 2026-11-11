package io.github.bags156.messagecenter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Group polls over plain texting, so they work with anyone (iPhone, any Android app, SMS):
 *
 * <pre>
 * 📊 Poll: Dinner Friday?
 * 1. Tacos
 * 2. Pizza
 * Reply with a number to vote.
 * </pre>
 *
 * People vote by replying with a number ("2", "#2", "2 (Pizza)") or an option's exact words
 * ("pizza"). Each person's latest vote counts. A vote is a reply to the poll, or any such message
 * after it within a few days and before the next poll in the chat; a reply to some other message
 * is never a vote. Plain Java, so it's tested without a phone.
 */
final class Polls {
    static final String MARK = "📊 Poll: ";
    static final int MOST_OPTIONS = 6;
    /** How long after a poll a bare "2" still counts as a vote (a reply to the poll always does). */
    static final long WINDOW_MS = 3 * 24 * 3_600_000L;

    static final class Poll {
        final String question;
        final List<String> options;
        Poll(String question, List<String> options) { this.question = question; this.options = options; }
    }

    /** One message in the chat, oldest first: who (a participant ID, or "me"), when, what, and what it replies to. */
    static final class Said {
        final String id, sender, text, replyTo;
        final long at;
        Said(String id, String sender, long at, String text, String replyTo) {
            this.id = id; this.sender = sender; this.at = at; this.text = text == null ? "" : text; this.replyTo = replyTo == null ? "" : replyTo;
        }
    }

    /** A poll's standing: counts per option, and each voter's choice (in the order they voted). */
    static final class Result {
        final Poll poll;
        final int[] counts;
        final Map<String, Integer> choices = new java.util.LinkedHashMap<>();
        Result(Poll poll) { this.poll = poll; counts = new int[poll.options.size()]; }
        int total() { int t = 0; for (int c : counts) t += c; return t; }
    }

    /** Everything about polls in a stretch of chat: results by poll message, and which messages were votes (poll ID, option). */
    static final class Board {
        final Map<String, Result> results = new HashMap<>();
        final Map<String, String> voteFor = new HashMap<>();
        final Map<String, Integer> voteOption = new HashMap<>();
    }

    /** The poll's text. Options are trimmed; empty ones are dropped. */
    static String compose(String question, List<String> options) {
        StringBuilder b = new StringBuilder(MARK).append(oneLine(question));
        int n = 0;
        for (String o : options) { String t = oneLine(o); if (t.isEmpty()) continue; b.append('\n').append(++n).append(". ").append(t); }
        return b.append("\nReply with a number to vote.").toString();
    }

    private static final Pattern HEAD = Pattern.compile("^(?:📊\\s*)?poll:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION = Pattern.compile("^(\\d{1,2})[.)]\\s+(.+)$");

    /** The poll in a message, or null. Options must be numbered 1, 2, 3… with at least two. */
    static Poll parse(String text) {
        if (text == null) return null;
        String[] lines = text.trim().split("\\r?\\n");
        if (lines.length < 3) return null;
        Matcher head = HEAD.matcher(lines[0].trim());
        if (!head.matches()) return null;
        List<String> options = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            Matcher o = OPTION.matcher(lines[i].trim());
            if (!o.matches() || Integer.parseInt(o.group(1)) != options.size() + 1) break;
            options.add(o.group(2).trim());
        }
        if (options.size() < 2 || options.size() > 10) return null;
        return new Poll(head.group(1).trim(), options);
    }

    private static final Pattern NUMBER = Pattern.compile("^#?\\s*(\\d{1,2})(.*)$", Pattern.DOTALL);

    /**
     * Which option a message votes for (0-based), or -1. A number counts on its own ("2", "2.",
     * "#2 👍") or with that option's words ("2 (Pizza)", "2 - pizza"); "2 more minutes" isn't a vote.
     */
    static int vote(String text, Poll poll) {
        if (text == null || poll == null) return -1;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 80) return -1;
        Matcher m = NUMBER.matcher(t);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > poll.options.size()) return -1;
            String rest = fold(m.group(2));
            return rest.isEmpty() || rest.equals(fold(poll.options.get(n - 1))) ? n - 1 : -1;
        }
        String said = fold(t);
        for (int i = 0; i < poll.options.size(); i++) if (fold(poll.options.get(i)).equals(said)) return i;
        return -1;
    }

    /** Works out every poll in the messages (oldest first) and who voted for what. */
    static Board board(List<Said> messages) {
        Board board = new Board();
        String open = null; Result openResult = null; long openedAt = 0;
        Map<String, Result> byId = new HashMap<>();
        for (Said s : messages) {
            Poll poll = parse(s.text);
            if (poll != null) {
                Result r = new Result(poll);
                board.results.put(s.id, r); byId.put(s.id, r);
                open = s.id; openResult = r; openedAt = s.at;
                continue;
            }
            // A reply to a poll votes on that poll, even after a newer one; a reply to anything else never votes.
            String target = null; Result r = null;
            if (!s.replyTo.isEmpty()) { r = byId.get(s.replyTo); if (r != null) target = s.replyTo; }
            else if (open != null && s.at - openedAt <= WINDOW_MS) { target = open; r = openResult; }
            if (r == null) continue;
            int choice = vote(s.text, r.poll);
            if (choice < 0) continue;
            Integer before = r.choices.remove(s.sender);
            if (before != null) r.counts[before]--;
            r.choices.put(s.sender, choice); r.counts[choice]++;
            board.voteFor.put(s.id, target); board.voteOption.put(s.id, choice);
        }
        return board;
    }

    /** What to send to vote: the number, and the option so it reads well on phones without this app. */
    static String ballot(Poll poll, int option) { return (option + 1) + " (" + poll.options.get(option) + ")"; }

    private static String oneLine(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }

    private static String fold(String s) {
        String plain = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").replaceAll("\\s+", " ").trim();
    }

    private Polls() {}
}
