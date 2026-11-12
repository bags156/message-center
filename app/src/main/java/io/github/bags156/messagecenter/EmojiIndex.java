package io.github.bags156.messagecenter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every emoji with its category, name and keywords (assets/emoji.tsv, built by tools/emoji_index.py),
 * for the emoji picker's tabs and its search.
 */
final class EmojiIndex {
    /** Smileys, people, animals, food, travel, activities, objects, symbols, flags. */
    static final int[] CATEGORIES = {0, 1, 3, 4, 5, 6, 7, 8, 9};
    static final String[] CATEGORY_NAMES = {"Smileys & emotion", "People", "Animals & nature", "Food & drink", "Travel & places", "Activities", "Objects", "Symbols", "Flags"};
    static final String[] CATEGORY_ICONS = {"😀", "👋", "🐻", "🍔", "✈️", "⚽", "💡", "🔣", "🏁"};
    /** The most used emoji, most used first (after Unicode's emoji frequency list): they win ties in search. */
    private static final String POPULAR = "😂 ❤️ 🤣 👍 😭 🙏 😘 🥰 😍 😊 🎉 😁 💕 🥺 😅 🔥 ☺️ 🤦 ♥️ 🤷 🙄 😆 🤗 😉 🎂 🤔 👏 🙂 😳 🥳 😎 👌 💜 😔 💪 ✨ 💖 👀 😋 😏"
        + " 😢 👉 💗 😩 💯 🌹 💞 🎈 💙 😃 😡 💐 😜 🙈 🤞 😄 🤤 🙌 🤪 ❣️ 😀 💋 💀 👇 💔 😌 💓 🤩 🙃 😬 😱 😴 🤭 😐 🌞 😒 😇 🌸 😈 🎶 ✌️ 🎊"
        + " 🥵 😞 💚 ☀️ 🖤 💰 😚 👑 🎁 💥 🙋 ☹️ 😑 🥴 👈 💩 ✅ 🐶 🍕 🍺 🎄 ⭐ 🌈 🙌 🤝 👋 ✔️ ❌ ⚠️ 📍 📷 🏆 ⚽ 🏀";
    private static final Map<String, Integer> RANK = new HashMap<>();
    static {
        String[] popular = POPULAR.split(" ");
        for (int i = 0; i < popular.length; i++) RANK.putIfAbsent(plain(popular[i]), i);
    }

    static final class Emoji {
        final String emoji, name;
        final int category, order;
        final double version;
        final String[] nameWords, keywords, skins;
        /** The Unicode emoji version each skin tone came in (phones before it can't draw it). */
        final double[] skinVersions;

        Emoji(String emoji, int category, double version, String name, String keywords, String skins, String skinVersions, int order) {
            this.emoji = emoji; this.category = category; this.version = version; this.name = name; this.order = order;
            this.nameWords = split(name.toLowerCase(Locale.ROOT).replaceAll("[:,]", " "));
            this.keywords = split(keywords);
            this.skins = split(skins);
            String[] v = split(skinVersions);
            this.skinVersions = new double[this.skins.length];
            for (int i = 0; i < this.skins.length; i++) this.skinVersions[i] = i < v.length ? Double.parseDouble(v[i]) : version;
        }

        boolean hasSkins() { return skins.length > 0; }

        int rank() { Integer r = RANK.get(plain(emoji)); return r == null ? Integer.MAX_VALUE : r; }
    }

    private final List<Emoji> all;
    private final Map<String, Emoji> byEmoji = new HashMap<>();

    private EmojiIndex(List<Emoji> all) {
        this.all = Collections.unmodifiableList(all);
        for (Emoji e : all) {
            byEmoji.put(plain(e.emoji), e);
            for (String skin : e.skins) byEmoji.putIfAbsent(plain(skin), e);
        }
    }

    static EmojiIndex read(Reader in) throws IOException {
        List<Emoji> all = new ArrayList<>();
        BufferedReader r = new BufferedReader(in);
        for (String line; (line = r.readLine()) != null; ) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 6) continue;
            try {
                all.add(new Emoji(f[0], Integer.parseInt(f[1]), Double.parseDouble(f[2]), f[3], f[4], f[5], f.length > 6 ? f[6] : "", all.size()));
            } catch (NumberFormatException ignored) {}
        }
        return new EmojiIndex(all);
    }

    static EmojiIndex empty() { return new EmojiIndex(new ArrayList<>()); }

    List<Emoji> all() { return all; }

    List<Emoji> category(int category) {
        List<Emoji> out = new ArrayList<>();
        for (Emoji e : all) if (e.category == category) out.add(e);
        return out;
    }

    /** The emoji this is, or the one it's a skin tone of; null if it isn't in the index. */
    Emoji find(String emoji) { return emoji == null ? null : byEmoji.get(plain(emoji)); }

    /**
     * Emoji whose name or keywords match every word typed ("smiling" counts for "smile"). A whole
     * name word counts most, then a whole keyword, then the start of a word; the name being exactly
     * what was typed, or every word of it typed, and being a much used emoji, count extra. Ties go to
     * the more used emoji, then Unicode's order.
     */
    List<Emoji> search(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[] terms = split(q);
        if (terms.length == 0) return new ArrayList<>();
        List<Emoji> found = new ArrayList<>();
        Map<Emoji, Integer> scores = new HashMap<>();
        for (Emoji e : all) {
            int total = 0;
            boolean[] named = new boolean[e.nameWords.length];
            for (String term : terms) {
                String stem = stem(term);
                int best = 0;
                for (int i = 0; i < e.nameWords.length; i++) {
                    String w = e.nameWords[i];
                    int score = w.equals(term) ? 6 : w.startsWith(term) || w.startsWith(stem) ? 3 : 0;
                    if (score > 0) named[i] = true;
                    best = Math.max(best, score);
                }
                for (String w : e.keywords) best = Math.max(best, w.equals(term) ? 4 : w.startsWith(term) || w.startsWith(stem) ? 2 : 0);
                if (best == 0 && term.length() >= 3 && e.name.toLowerCase(Locale.ROOT).contains(term)) best = 1;
                if (best == 0) { total = 0; break; }
                total += best;
            }
            if (total == 0) continue;
            boolean whole = named.length > 0;
            for (boolean n : named) whole &= n;
            if (e.name.toLowerCase(Locale.ROOT).equals(q)) total += 20;
            else if (whole) total += 4;
            if (e.rank() != Integer.MAX_VALUE) total += 3;
            scores.put(e, total);
            found.add(e);
        }
        found.sort((a, b) -> !scores.get(a).equals(scores.get(b)) ? Integer.compare(scores.get(b), scores.get(a))
            : a.rank() != b.rank() ? Integer.compare(a.rank(), b.rank()) : Integer.compare(a.order, b.order));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    /** Without the emoji/text presentation selectors, so "❤" and "❤️" are the same emoji. */
    static String plain(String emoji) { return emoji.replace("️", "").replace("︎", ""); }

    /** "smiling", "smiled", "smiles" and "smile" all to "smil", so one finds the others. */
    static String stem(String word) {
        if (word.length() > 5 && word.endsWith("ing")) return word.substring(0, word.length() - 3);
        if (word.length() > 4 && word.endsWith("ed")) return word.substring(0, word.length() - 2);
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) return word.substring(0, word.length() - 1);
        if (word.length() > 4 && word.endsWith("e")) return word.substring(0, word.length() - 1);
        return word;
    }

    private static String[] split(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }
}
