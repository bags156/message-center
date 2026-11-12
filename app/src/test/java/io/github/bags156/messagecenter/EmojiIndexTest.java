package io.github.bags156.messagecenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

public class EmojiIndexTest {
    private static EmojiIndex index;

    @BeforeClass public static void load() throws Exception {
        File f = new File("src/main/assets/emoji.tsv");
        if (!f.isFile()) f = new File("app/src/main/assets/emoji.tsv");
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) { index = EmojiIndex.read(r); }
    }

    private static List<String> found(String query) {
        List<String> out = new ArrayList<>();
        for (EmojiIndex.Emoji e : index.search(query, 50)) out.add(e.emoji);
        return out;
    }

    @Test public void everyTabHasEmoji() {
        assertTrue(index.all().size() > 1800);
        for (int c : EmojiIndex.CATEGORIES) assertTrue("category " + c, index.category(c).size() > 50);
        assertTrue(index.category(2).isEmpty());
        assertEquals(EmojiIndex.CATEGORIES.length, EmojiIndex.CATEGORY_NAMES.length);
        assertEquals(EmojiIndex.CATEGORIES.length, EmojiIndex.CATEGORY_ICONS.length);
    }

    @Test public void searchFindsByNameFirst() {
        assertEquals("🔥", found("fire").get(0));
        assertEquals("🍕", found("pizza").get(0));
        assertEquals("👍", found("thumbs up").get(0));
        assertEquals("🐱", found("Cat Face").get(0));
        assertEquals("👍", found("thu").get(0));
    }

    @Test public void searchFindsByKeyword() {
        assertTrue(found("lol").indexOf("😂") >= 0 && found("lol").indexOf("😂") < 5);
        assertTrue(found("laugh").contains("😂"));
        assertTrue(found("lit").contains("🔥"));
        assertTrue(found("<3").contains("❤️"));
        assertTrue(found("+1").contains("👍"));
        assertTrue(found("love heart").contains("❤️"));
    }

    @Test public void searchNeedsEveryWord() {
        assertTrue(found("").isEmpty());
        assertTrue(found("   ").isEmpty());
        assertTrue(found("zzqxv").isEmpty());
        assertFalse(found("fire truck").contains("🔥"));
        assertTrue(found("fire truck").contains("🚒"));
        assertTrue(index.search("a", 7).size() <= 7);
    }

    @Test public void findsSkinTonesAndPresentation() {
        assertEquals("👍", index.find("👍🏽").emoji);
        assertEquals("❤️", index.find("❤").emoji);
        assertEquals(5, index.find("👍").skins.length);
        assertEquals(5, index.find("👍").skinVersions.length);
        assertTrue(index.find("👍").skinVersions[0] >= 1 && index.find("👍").skinVersions[0] <= 2);
        assertEquals(0, index.find("🔥").skinVersions.length);
        assertTrue(EmojiIndex.empty().search("fire", 5).isEmpty());
        assertNull(index.find("abc"));
        assertNull(index.find(null));
    }
}
