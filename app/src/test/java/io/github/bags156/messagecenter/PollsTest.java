package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PollsTest {
    private static final long T = 1_800_000_000_000L, HOUR = 3_600_000L;
    private static Polls.Said said(String id, String who, long at, String text) { return new Polls.Said(id, who, at, text, ""); }
    private static Polls.Said reply(String id, String who, long at, String text, String to) { return new Polls.Said(id, who, at, text, to); }

    @Test public void composedPollsParseBack() {
        String text = Polls.compose("  Dinner\nFriday? ", Arrays.asList("Tacos", " ", "Pizza", "Sushi  bar"));
        assertEquals("📊 Poll: Dinner Friday?\n1. Tacos\n2. Pizza\n3. Sushi bar\nReply with a number to vote.", text);
        Polls.Poll p = Polls.parse(text);
        assertNotNull(p); assertEquals("Dinner Friday?", p.question); assertEquals(Arrays.asList("Tacos", "Pizza", "Sushi bar"), p.options);
        // Typed by hand, without the emoji or the footer, still counts.
        Polls.Poll typed = Polls.parse("Poll: Movie?\n1) Dune\n2) Up");
        assertNotNull(typed); assertEquals(2, typed.options.size());
    }

    @Test public void ordinaryMessagesAreNotPolls() {
        assertNull(Polls.parse("Poll: just one?\n1. Yes"));
        assertNull(Polls.parse("Shopping list:\n1. Eggs\n2. Milk"));
        assertNull(Polls.parse("Poll: skipped\n1. A\n3. B"));
        assertNull(Polls.parse("Hello"));
        assertNull(Polls.parse(null));
    }

    @Test public void votesCanBeANumberOrTheOptionsWords() {
        Polls.Poll p = Polls.parse(Polls.compose("Dinner?", Arrays.asList("Tacos", "Pizza", "Crème brûlée")));
        assertEquals(1, Polls.vote("2", p)); assertEquals(1, Polls.vote(" #2 ", p)); assertEquals(1, Polls.vote("2.", p));
        assertEquals(1, Polls.vote("2 (Pizza)", p)); assertEquals(1, Polls.vote("2 - pizza!", p)); assertEquals(0, Polls.vote("1 👍", p));
        assertEquals(1, Polls.vote("PIZZA", p)); assertEquals(2, Polls.vote("creme brulee", p));
        assertEquals(1, Polls.vote(Polls.ballot(p, 1), p));
        assertEquals(-1, Polls.vote("4", p)); assertEquals(-1, Polls.vote("0", p)); assertEquals(-1, Polls.vote("2 more minutes", p));
        assertEquals(-1, Polls.vote("I like pizza", p)); assertEquals(-1, Polls.vote("", p)); assertEquals(-1, Polls.vote("2", null));
    }

    @Test public void eachPersonsLatestVoteCounts() {
        String poll = Polls.compose("Dinner?", Arrays.asList("Tacos", "Pizza"));
        List<Polls.Said> chat = new ArrayList<>(Arrays.asList(said("p", "me", T, poll), said("a", "sam", T + 1000, "1"), said("b", "alex", T + 2000, "pizza"),
                said("c", "sam", T + 3000, "2 (Pizza)"), said("d", "me", T + 4000, "2"), said("e", "alex", T + 5000, "lol")));
        Polls.Board board = Polls.board(chat);
        Polls.Result r = board.results.get("p");
        assertNotNull(r); assertEquals(0, r.counts[0]); assertEquals(3, r.counts[1]); assertEquals(3, r.total());
        assertEquals(Integer.valueOf(1), r.choices.get("sam")); assertEquals(Integer.valueOf(1), r.choices.get("me"));
        assertEquals("p", board.voteFor.get("a")); assertEquals(Integer.valueOf(0), board.voteOption.get("a"));
        assertFalse(board.voteFor.containsKey("e"));
    }

    @Test public void repliesElsewhereAndLateNumbersAreNotVotes() {
        String poll = Polls.compose("Dinner?", Arrays.asList("Tacos", "Pizza"));
        Polls.Board board = Polls.board(Arrays.asList(said("p", "me", T, poll), said("q", "sam", T + 1000, "How many are coming?"),
                reply("a", "alex", T + 2000, "2", "q"), said("late", "kim", T + Polls.WINDOW_MS + HOUR, "1"),
                reply("lateReply", "lee", T + Polls.WINDOW_MS + HOUR, "1", "p")));
        Polls.Result r = board.results.get("p");
        assertEquals(1, r.counts[0]); assertEquals(0, r.counts[1]);
        assertFalse(board.voteFor.containsKey("a")); assertFalse(board.voteFor.containsKey("late")); assertTrue(board.voteFor.containsKey("lateReply"));
    }

    @Test public void aNewerPollTakesOverBareNumbersButRepliesStillReachTheOldOne() {
        Polls.Board board = Polls.board(Arrays.asList(said("p1", "me", T, Polls.compose("Day?", Arrays.asList("Fri", "Sat"))),
                said("p2", "sam", T + HOUR, Polls.compose("Time?", Arrays.asList("6", "7", "8"))),
                said("a", "alex", T + 2 * HOUR, "3"), reply("b", "kim", T + 2 * HOUR, "2", "p1")));
        assertEquals(1, board.results.get("p2").counts[2]); assertEquals(1, board.results.get("p1").counts[1]);
        assertEquals("p2", board.voteFor.get("a")); assertEquals("p1", board.voteFor.get("b"));
    }
}
