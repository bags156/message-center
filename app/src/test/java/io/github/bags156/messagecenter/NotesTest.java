package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class NotesTest {
    private static Notes.Note note(String id, String text, long created, boolean pinned, String... tags) {
        Notes.Note n = new Notes.Note(id, text, created);
        n.pinned = pinned;
        for (String t : tags) n.tags = Notes.withTag(n.tags, t);
        return n;
    }

    @Test public void aPersonsNotesFollowTheirNumber() {
        assertEquals("p:4795550100", Notes.personKey("+1 (479) 555-0100"));
        assertEquals(Notes.personKey("+14795550100"), Notes.personKey("479-555-0100"));
        assertEquals("", Notes.personKey(""));
        assertEquals("", Notes.personKey(null));
        assertEquals("g:abc", Notes.groupKey("abc"));
        assertEquals("", Notes.groupKey(""));
    }

    @Test public void pinnedFirstThenNewest() {
        List<Notes.Note> sorted = Notes.sorted(Arrays.asList(note("a", "old", 100, false), note("b", "new", 300, false), note("c", "pinned old", 50, true), note("d", "pinned new", 200, true)));
        assertEquals("d", sorted.get(0).id);
        assertEquals("c", sorted.get(1).id);
        assertEquals("b", sorted.get(2).id);
        assertEquals("a", sorted.get(3).id);
    }

    @Test public void tagsAreCleanedAndNotDoubled() {
        assertEquals("gift ideas", Notes.tag("  #gift   ideas "));
        assertEquals("", Notes.tag("##  "));
        assertEquals(Notes.MAX_TAG, Notes.tag("x".repeat(50)).length());
        List<String> tags = Notes.withTag(Notes.withTag(Arrays.asList(), "Gift"), "gift");
        assertEquals(Arrays.asList("Gift"), tags);
        assertEquals(Arrays.asList("gift", "Birthday"), Notes.splitTags("gift, Birthday,,#gift"));
    }

    @Test public void mostUsedTagsFirst() {
        List<String> tags = Notes.tagsOf(Arrays.asList(note("a", "", 1, false, "birthday", "Gift"), note("b", "", 2, false, "gift"), note("c", "", 3, false, "anniversary")));
        assertEquals(Arrays.asList("Gift", "anniversary", "birthday"), tags);
    }

    @Test public void searchLooksAtTextTagsAndSource() {
        Notes.Note n = note("a", "Wants the blue scarf\nAsk about Café Luna", 1, false, "gift ideas", "Winter");
        n.source = "Nora's message, Nov 12";
        assertTrue(Notes.matches(n, ""));
        assertTrue(Notes.matches(n, "SCARF"));
        assertTrue(Notes.matches(n, "cafe luna"));
        assertTrue(Notes.matches(n, "gift"));
        assertTrue(Notes.matches(n, "#gift"));
        assertTrue(Notes.matches(n, "#winter scarf"));
        assertTrue(Notes.matches(n, "nora"));
        assertFalse(Notes.matches(n, "#scarf"));
        assertFalse(Notes.matches(n, "scarf tickets"));
        assertTrue(Notes.hasTag(n, "WINTER"));
        assertFalse(Notes.hasTag(n, "gift"));
    }

    @Test public void notesSurviveSaving() throws Exception {
        Notes.Note n = note("a", "Birthday May 3", 1000, true, "birthday", "family");
        n.updated = 2000;
        n.source = "Nora's message, Nov 12";
        Notes.Note back = Notes.Note.from(n.json());
        assertEquals("a", back.id);
        assertEquals("Birthday May 3", back.text);
        assertEquals(Arrays.asList("birthday", "family"), back.tags);
        assertTrue(back.pinned);
        assertEquals(1000, back.created);
        assertEquals(2000, back.updated);
        assertEquals("Nora's message, Nov 12", back.source);
        assertTrue(new Notes.Note("x", "  ", 1).blank());
        assertFalse(note("x", "", 1, false, "gift").blank());
        assertNotEquals(Notes.newId(5), "");
    }

    @Test public void searchSnippetIsTheMatchingLine() {
        String notes = "Birthday: May 3\nGift ideas: blue scarf\nAsk how the new job is going";
        assertEquals("Gift ideas: blue scarf", Notes.snippet(notes, "scarf"));
        assertEquals("Ask how the new job is going", Notes.snippet(notes, "#work job"));
        assertEquals("Birthday: May 3", Notes.snippet(notes, "#gift"));
        String long1 = "Things to remember: " + "x".repeat(80) + " the anniversary dinner place " + "y".repeat(80);
        String cut = Notes.snippet(long1, "anniversary");
        assertTrue(cut, cut.contains("anniversary") && cut.startsWith("…") && cut.endsWith("…") && cut.length() <= 92);
    }
    @Test public void attachmentsAreKeptWithTheNote() throws Exception {
        Notes.Note n = note("k3x9", "", 1, false);
        n.attachments.add(new Notes.Attachment("1-scarf.jpg", "scarf.jpg", "image/jpeg"));
        n.attachments.add(new Notes.Attachment("2-list.pdf", "Gift list.pdf", ""));
        assertFalse(n.blank());
        Notes.Note back = Notes.Note.from(n.json());
        assertEquals(2, back.attachments.size());
        assertEquals("1-scarf.jpg", back.attachments.get(0).file);
        assertTrue(back.attachments.get(0).image());
        assertEquals("Gift list.pdf", back.attachments.get(1).name);
        assertEquals("application/octet-stream", back.attachments.get(1).mime);
        assertEquals(2, back.copy().attachments.size());
    }

    @Test public void attachmentNamesAndFoldersAreSafe() {
        assertEquals("3-Gift_list.pdf", Notes.fileName("Gift list.pdf", 3));
        assertEquals("1-etc_passwd", Notes.fileName("../../etc/passwd", 1));
        assertEquals("2-file", Notes.fileName("", 2));
        assertTrue(Notes.fileName("x".repeat(200) + ".jpg", 4).length() <= 63);
        java.io.File files = new java.io.File("/data/app");
        assertEquals(new java.io.File(new java.io.File(files, "notes"), "k3x9a"), Notes.dir(files, "k3x9a"));
        assertEquals(null, Notes.dir(files, "../x"));
        assertEquals(null, Notes.dir(files, ""));
        assertEquals(null, Notes.dir(files, "ABC"));
    }
    @Test public void addingToANoteGoesOnANewLine() {
        assertEquals("Gift: scarf", Notes.appended("", "Gift: scarf"));
        assertEquals("Birthday May 3\nGift: scarf", Notes.appended("Birthday May 3\n\n  ", " Gift: scarf "));
        assertEquals("Birthday May 3", Notes.appended("Birthday May 3", "   "));
    }
}
