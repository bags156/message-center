package io.github.bags156.messagecenter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presentation rules for message text. Pure Java so they can be unit tested. */
final class ChatText {
    private ChatText() {}

    private static final Pattern REPLY_FALLBACK = Pattern.compile("^Replied to a message:[ \\t]*\\R?(.*)$", Pattern.DOTALL);
    private static final Pattern MEDIA_REACTION = Pattern.compile(
        "^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned) (an image|a photo|a video|a movie|an attachment|an audio message|a sticker|a GIF|a link)$");

    /** A text reply notice can arrive without a usable structured reference in the paired feed. */
    static String replyFallbackBody(String body) {
        if (body == null) return null;
        Matcher m = REPLY_FALLBACK.matcher(body.trim());
        return m.matches() ? m.group(1).trim() : null;
    }

    private static final String MEDIA_OBJECT = "(an image|a photo|a video|a movie|an attachment|an audio message|a sticker|a GIF|a link)";
    private static final Pattern MEDIA_REMOVED = Pattern.compile(
        "^Removed (a heart|a like|a dislike|a laugh|an emphasis|a question mark) from " + MEDIA_OBJECT + "$");
    private static final Pattern MEDIA_CUSTOM = Pattern.compile("^(Reacted (.+?) to|Removed (.+?) from) " + MEDIA_OBJECT + "$");

    /** "Loved an image" style iPhone fallbacks: {emoji, verb, object}, or null. */
    static String[] mediaReaction(String body) {
        if (body == null) return null;
        Matcher m = MEDIA_REACTION.matcher(ReactionParser.clean(body));
        if (!m.matches()) return null;
        return new String[]{emoji(m.group(1)), m.group(1).toLowerCase(Locale.ROOT), m.group(2)};
    }

    /**
     * Reaction text an iPhone sends instead of a real reaction ("Laughed at an image",
     * Loved “See you soon”, "Removed a laugh from an image", "Reacted 🎉 to a photo").
     * Exactly one of {@code object} (what kind of attachment) and {@code quote} is set.
     */
    static final class Tapback {
        final String emoji, object, quote; final boolean removed;
        Tapback(String emoji, String object, String quote, boolean removed) { this.emoji = emoji; this.object = object; this.quote = quote; this.removed = removed; }
    }

    static Tapback tapback(String body) {
        if (body == null) return null;
        String t = ReactionParser.clean(body);
        String[] media = mediaReaction(t);
        if (media != null) return new Tapback(media[0], media[2], null, false);
        Matcher removed = MEDIA_REMOVED.matcher(t);
        if (removed.matches()) return new Tapback(removedEmoji(removed.group(1)), removed.group(2), null, true);
        Matcher custom = MEDIA_CUSTOM.matcher(t);
        if (custom.matches()) {
            String emoji = custom.group(2) != null ? custom.group(2) : custom.group(3);
            if (ReactionParser.isEmoji(emoji)) return new Tapback(emoji, custom.group(4), null, custom.group(3) != null);
        }
        ReactionParser.Reaction r = ReactionParser.parse(t);
        return r == null ? null : new Tapback(r.emoji, null, r.quote, r.removed);
    }

    private static String removedEmoji(String what) {
        switch (what) {
            case "a heart": return "❤️";
            case "a like": return "👍";
            case "a dislike": return "👎";
            case "a laugh": return "😂";
            case "an emphasis": return "‼️";
            default: return "❓";
        }
    }

    /** A message as the reaction matcher sees it: who sent it, its typed text, and what's attached. */
    static final class Said {
        final String sender, text; final boolean image, video, audio, file;
        Said(String sender, String text, boolean image, boolean video, boolean audio, boolean file) {
            this.sender = sender == null ? "" : sender; this.text = text == null ? "" : text;
            this.image = image; this.video = video; this.audio = audio; this.file = file;
        }
    }

    private static final int TAPBACK_LOOKBACK = 300;
    private static final Pattern LINK = Pattern.compile("(?i)\\b(https?://|www\\.)\\S+");

    /**
     * The message an iPhone reaction at {@code at} is about, or -1 when it can't be told.
     * Quoted text goes to the latest earlier message with that text (a quote ending in "…"
     * matches by its start). "An image" and the like go to the latest earlier message with
     * that kind of attachment, someone else's before the reactor's own.
     */
    static int tapbackTarget(Tapback t, int at, List<Said> said, boolean[] skip) {
        if (t == null || at <= 0 || at > said.size()) return -1;
        String reactor = at < said.size() ? said.get(at).sender : "";
        int stop = Math.max(0, at - TAPBACK_LOOKBACK), own = -1;
        String quote = t.quote == null ? null : squash(t.quote);
        boolean prefix = quote != null && (quote.endsWith("…") || quote.endsWith("..."));
        if (prefix) quote = quote.replaceAll("(…|\\.\\.\\.)$", "").trim();
        for (int j = at - 1; j >= stop; j--) {
            if (skip != null && j < skip.length && skip[j]) continue;
            Said s = said.get(j);
            if (quote != null) {
                String text = squash(s.text);
                if (quote.isEmpty() || text.isEmpty()) continue;
                if (prefix ? text.startsWith(quote) : text.equals(quote)) return j;
                continue;
            }
            if (!fits(t.object, s)) continue;
            if (!s.sender.equals(reactor) || reactor.isEmpty()) return j;
            if (own < 0) own = j;
        }
        return own;
    }

    private static boolean fits(String object, Said s) {
        switch (object) {
            case "an image": case "a photo": case "a sticker": case "a GIF": return s.image;
            case "a video": case "a movie": return s.video;
            case "an audio message": return s.audio;
            case "a link": return LINK.matcher(s.text).find();
            default: return s.image || s.video || s.audio || s.file;
        }
    }

    /** Text as compared for a quote: invisible marks and space differences ignored, curly and straight quotes alike. */
    private static String squash(String text) {
        return ReactionParser.clean(text).replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"');
    }

    static String emoji(String verb) {
        switch (verb) {
            case "Loved": return "❤️";
            case "Liked": return "👍";
            case "Disliked": return "👎";
            case "Laughed at": return "😂";
            case "Emphasized": return "‼️";
            default: return "❓";
        }
    }

    /**
     * A one-line description for reaction text an iPhone sent as a regular message, or null
     * when the text is an ordinary message. Used where the reaction can't be put on the
     * message it's about (see {@link #tapbackTarget}), and for notifications and the inbox.
     */
    static String reactionLine(String sender, String body) {
        String[] media = mediaReaction(body);
        if (media != null) return sender + " " + media[1] + " " + media[2] + " " + media[0];
        Tapback t = tapback(body);
        if (t != null && t.object != null) return sender + (t.removed ? " removed " + t.emoji + " from " : " reacted " + t.emoji + " to ") + t.object;
        ReactionParser.Reaction r = ReactionParser.parse(body);
        if (r == null) return null;
        String quote = r.quote.replaceAll("\\s+", " ").trim();
        if (quote.length() > 60) quote = quote.substring(0, 59) + "…";
        return sender + (r.removed ? " removed " + r.emoji + " from “" : " reacted " + r.emoji + " to “") + quote + "”";
    }

    /**
     * Dictated words as they should be inserted after {@code before}: a space if they'd run into
     * the previous word, and a capital letter at the start of the message or a new sentence.
     */
    static String dictation(String before, String heard) {
        String words = heard == null ? "" : heard.trim();
        if (words.isEmpty()) return "";
        String prior = before == null ? "" : before;
        String trimmed = prior.replaceAll("\\s+$", "");
        boolean sentence = trimmed.isEmpty() || trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") || prior.endsWith("\n");
        if (sentence) words = words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
        if (!prior.isEmpty() && !Character.isWhitespace(prior.charAt(prior.length() - 1))) words = " " + words;
        return words;
    }

    /** Phone numbers compared on their last ten digits, so "+1 (555) 010-2000" matches "5550102000". */
    static boolean sameNumber(String a, String b) {
        String x = digits(a), y = digits(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        int n = Math.min(10, Math.min(x.length(), y.length()));
        return x.substring(x.length() - n).equals(y.substring(y.length() - n));
    }

    static String digits(String value) { return value == null ? "" : value.replaceAll("\\D", ""); }

    /** "Daniel is typing", "Daniel and Sam are typing", "3 people are typing". */
    static String typingLabel(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0) + " is typing";
        if (names.size() == 2) return names.get(0) + " and " + names.get(1) + " are typing";
        return names.size() + " people are typing";
    }

    /** Up to three distinct emoji and the total count, e.g. "❤️👍 3". */
    static String reactionSummary(List<String[]> reactors) {
        StringBuilder emoji = new StringBuilder(); java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String[] r : reactors) if (seen.add(r[0]) && seen.size() <= 3) emoji.append(r[0]);
        return reactors.size() > 1 ? emoji + " " + reactors.size() : emoji.toString();
    }

    /**
     * A typed number as +country digits: "(479) 555-0100" and "1 479 555 0100" become
     * "+14795550100" (US default); numbers already starting with + keep their country code.
     * Returns "" when it does not look like a phone number.
     */
    static String normalizeNumber(String typed) {
        if (typed == null) return "";
        String t = typed.trim();
        if (!t.matches("[+0-9()\\s.\\-]{4,}")) return "";
        String d = digits(t);
        if (t.startsWith("+")) return d.length() >= 7 && d.length() <= 15 ? "+" + d : "";
        if (d.length() == 10) return "+1" + d;
        if (d.length() == 11 && d.startsWith("1")) return "+" + d;
        return "";
    }

    private static final Pattern URL = Pattern.compile("(?i)\\b(https?://[^\\s<>\"]+|www\\.[^\\s<>\"]+)");

    /** The first web link in a message (trailing punctuation dropped), as https:// when no scheme was typed; "" if none. */
    static String firstUrl(String text) {
        if (text == null) return "";
        Matcher m = URL.matcher(text);
        if (!m.find()) return "";
        String url = m.group(1).replaceAll("[.,!?;:)\\]'’”]+$", "");
        if (url.toLowerCase(Locale.ROOT).startsWith("www.")) url = "https://" + url;
        return url;
    }

    /** Every web link in a message, in order and without repeats, cleaned up like firstUrl. */
    static java.util.List<String> urls(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null) return out;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String url = m.group(1).replaceAll("[.,!?;:)\\]'’”]+$", "");
            if (url.toLowerCase(Locale.ROOT).startsWith("www.")) url = "https://" + url;
            if (!out.contains(url)) out.add(url);
        }
        return out;
    }

    /** "example.com" from a link, without "www.". */
    static String domain(String url) {
        String rest = url.replaceFirst("(?i)^https?://", "");
        int end = rest.length();
        for (char c : new char[]{'/', '?', '#', ':'}) { int i = rest.indexOf(c); if (i >= 0 && i < end) end = i; }
        String host = rest.substring(0, end).toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static final Pattern CODE_HINT = Pattern.compile("(?i)(code|passcode|verif|otp|one[- ]time|\\bpin\\b|log ?in|sign[- ]?in|security|confirm|authenticat|2fa|is your)");
    private static final Pattern CODE = Pattern.compile("(?<![\\d$.,-])(?:[A-Z]-)?(\\d{3}[- ]\\d{3}|\\d{4,8})(?![\\d.,]\\d|\\d|-\\d)");

    /**
     * A one-time code ("Your verification code is 482913", "G-382915 is your Google code"), or ""
     * when the message has no code-like wording. Phone numbers, prices and long numbers are skipped.
     */
    static String oneTimeCode(String text) {
        if (text == null || !CODE_HINT.matcher(text).find()) return "";
        Matcher m = CODE.matcher(text);
        while (m.find()) {
            String digits = m.group(1).replaceAll("[- ]", "");
            if (digits.length() >= 4 && digits.length() <= 8) return digits;
        }
        return "";
    }

    /** How many emoji the message is made of (1-3) when it is only emoji, so they can be shown large; otherwise 0. */
    static int emojiOnly(String text) {
        if (text == null) return 0;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 48) return 0;
        int count = 0, i = 0; boolean join = false, regional = false;
        while (i < t.length()) {
            int cp = t.codePointAt(i), n = Character.charCount(cp);
            if (Character.isWhitespace(cp)) { i += n; continue; }
            if (cp == 0x200D) { join = true; i += n; continue; }
            if (cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || (cp >= 0x1F3FB && cp <= 0x1F3FF) || (cp >= 0xE0020 && cp <= 0xE007F)) { i += n; continue; }
            boolean keycap = (cp == '#' || cp == '*' || (cp >= '0' && cp <= '9')) && t.indexOf(0x20E3, i) > i && t.indexOf(0x20E3, i) <= i + 2;
            if (!keycap && !emojiBase(cp)) return 0;
            boolean isRegional = cp >= 0x1F1E6 && cp <= 0x1F1FF;
            if (join) join = false;
            else if (isRegional && regional) regional = false;
            else { count++; regional = isRegional; }
            i += n;
        }
        return count >= 1 && count <= 3 ? count : 0;
    }

    /** The first emoji in the text, whole (skin tone, ZWJ family, flag, keycap), or "" if there's none. */
    static String firstEmoji(String text) {
        if (text == null) return "";
        int i = 0, start = -1;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            boolean keycap = (cp == '#' || cp == '*' || (cp >= '0' && cp <= '9')) && text.indexOf(0x20E3, i) > i && text.indexOf(0x20E3, i) <= i + 2;
            if (keycap || emojiBase(cp)) { start = i; break; }
            i += Character.charCount(cp);
        }
        if (start < 0) return "";
        int first = text.codePointAt(start), end = start + Character.charCount(first);
        boolean regional = first >= 0x1F1E6 && first <= 0x1F1FF, paired = false;
        while (end < text.length()) {
            int cp = text.codePointAt(end), n = Character.charCount(cp);
            if (cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || (cp >= 0x1F3FB && cp <= 0x1F3FF) || (cp >= 0xE0020 && cp <= 0xE007F)) { end += n; continue; }
            if (cp == 0x200D && end + n < text.length()) { int next = text.codePointAt(end + n); end += n + Character.charCount(next); continue; }
            if (regional && !paired && cp >= 0x1F1E6 && cp <= 0x1F1FF) { paired = true; end += n; continue; }
            break;
        }
        return text.substring(start, end);
    }

    private static boolean emojiBase(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x2300 && cp <= 0x23FF) || (cp >= 0x2B00 && cp <= 0x2BFF)
            || (cp >= 0x2190 && cp <= 0x21FF) || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 || cp == 0x00A9 || cp == 0x00AE
            || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 || cp == 0x24C2 || (cp >= 0x25AA && cp <= 0x25FE);
    }

    /** First name for compact labels; falls back to the whole name. */
    static String firstName(String name) {
        if (name == null) return "";
        String t = name.trim(); int space = t.indexOf(' ');
        return space > 0 && !t.startsWith("+") ? t.substring(0, space) : t;
    }

    private static final Pattern META = Pattern.compile("(?is)<meta\\s+((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)/?>");
    private static final Pattern ATTR = Pattern.compile("(?is)([a-z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    /** property/name → content for every meta tag, whatever the attribute order. */
    static java.util.Map<String, String> metaTags(String html) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        Matcher m = META.matcher(html);
        while (m.find()) {
            String key = "", content = null;
            Matcher a = ATTR.matcher(m.group(1));
            while (a.find()) {
                String name = a.group(1).toLowerCase(Locale.ROOT), value = a.group(3) != null ? a.group(3) : a.group(4);
                if (name.equals("property") || name.equals("name")) key = value.toLowerCase(Locale.ROOT).trim();
                else if (name.equals("content")) content = value;
            }
            if (!key.isEmpty() && content != null && !out.containsKey(key)) out.put(key, clean(content));
        }
        return out;
    }

    static String clean(String s) {
        return s.replaceAll("(?s)<[^>]+>", "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }


    /** True when the text @-mentions one of these names (whole word, any case). */
    static boolean mentions(String text, java.util.Collection<String> names) {
        if (text == null || text.indexOf('@') < 0 || names == null) return false;
        for (String name : names) {
            if (name == null || name.trim().length() < 2) continue;
            Pattern p = Pattern.compile("(^|[^\\w@])@" + Pattern.quote(name.trim()) + "(?![\\w])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    private static final Pattern GENERIC_TITLE = Pattern.compile(
        "(?i)^(robot check|access denied|just a moment\\.*|attention required!?.*|sorry[!,.].*|page not found|not found|forbidden|error|404.*|403.*|503.*|captcha.*|verify.*human.*|are you a robot\\??)$");
    private static final Pattern PRODUCT_SLUG = Pattern.compile("/([^/?#]{6,})/(?:dp|gp/product)/[A-Z0-9]{10}");

    /**
     * A usable title for a link preview, or "" when the page only gave a generic one (a site
     * name, or a bot check like Amazon's). Shopping titles lose their store prefix and
     * category suffix; a product link can fall back to the name in its address.
     */
    static String previewTitle(String title, String site, String finalUrl) {
        String host = domain(finalUrl == null ? "" : finalUrl).toLowerCase(Locale.ROOT);
        String t = title == null ? "" : title.trim();
        boolean amazon = host.matches("(.*\\.)?amazon\\.[a-z.]+") || host.equals("a.co");
        if (amazon) t = t.replaceFirst("(?i)^amazon(\\.[a-z]+)+\\s*:\\s*", "").replaceFirst("\\s+:\\s+[^:]{1,40}$", "").trim();
        String lower = t.toLowerCase(Locale.ROOT), bare = host.startsWith("www.") ? host.substring(4) : host;
        boolean generic = lower.isEmpty() || lower.equals(bare) || lower.equals("www." + bare) || lower.equals(bare.replaceFirst("\\.[a-z.]+$", ""))
            || (site != null && lower.equals(site.trim().toLowerCase(Locale.ROOT))) || GENERIC_TITLE.matcher(lower).matches()
            || (amazon && lower.startsWith("amazon"));
        if (!generic) return t;
        Matcher slug = PRODUCT_SLUG.matcher(finalUrl == null ? "" : finalUrl.replaceFirst("(?i)^https?://[^/]+", "")); // the path only
        if (slug.find()) {
            String name;
            try { name = java.net.URLDecoder.decode(slug.group(1), "UTF-8"); } catch (Exception e) { name = slug.group(1); }
            name = name.replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
            if (name.length() >= 6 && !name.equalsIgnoreCase("ref")) return name;
        }
        return "";
    }

    private static final String STREET_SUFFIX = "(?:street|st|avenue|ave|av|road|rd|boulevard|blvd|drive|dr|lane|ln|way|court|ct|place|pl|parkway|pkwy|"
        + "highway|hwy|circle|cir|terrace|ter|trail|trl|loop|square|sq|plaza|plz|alley|aly|crossing|xing|pike|run|path|cove|cv|point|pt|"
        + "expressway|expy|freeway|fwy|row|ridge|rdg|hollow|holw|bend|park|pass|walk|crescent|cres|close|grove|grv|heights|hts|turnpike|tpke)";
    private static final String STATES = "(?:AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY|"
        + "Alabama|Alaska|Arizona|Arkansas|California|Colorado|Connecticut|Delaware|Florida|Georgia|Hawaii|Idaho|Illinois|Indiana|Iowa|Kansas|Kentucky|Louisiana|Maine|Maryland|"
        + "Massachusetts|Michigan|Minnesota|Mississippi|Missouri|Montana|Nebraska|Nevada|New Hampshire|New Jersey|New Mexico|New York|North Carolina|North Dakota|Ohio|Oklahoma|"
        + "Oregon|Pennsylvania|Rhode Island|South Carolina|South Dakota|Tennessee|Texas|Utah|Vermont|Virginia|Washington|West Virginia|Wisconsin|Wyoming)";
    private static final String DIRECTION = "(?:north|south|east|west|n|s|e|w|ne|nw|se|sw)\\.?";
    /**
     * Street addresses written the US way: a house number, an optional direction, one to four words
     * of street name, and a street type ("805 SE J St", "123 Main Street Apt 4, Bentonville, AR 72712"),
     * or a numbered highway ("1200 Hwy 71"). A city, state, and ZIP after it are included.
     */
    private static final Pattern ADDRESS = Pattern.compile(
        "(?<![\\w$:./#-])\\d{1,6}[A-Za-z]?(?:-\\d{1,4})?\\s+(?:" + DIRECTION + "\\s+)?"
        + "(?:(?:[A-Za-z0-9][A-Za-z0-9'.-]*\\s+){0,3}[A-Za-z0-9][A-Za-z0-9'.-]*\\s+" + STREET_SUFFIX + "\\b\\.?(?:\\s+" + DIRECTION + "(?![\\w']))?"
        + "|(?:highway|hwy|us|state route|sr|route|rte|county road|cr|farm to market|fm)\\s+\\d{1,4}[A-Za-z]?\\b)"
        + "(?:,?\\s+(?:apt|apartment|suite|ste|unit|bldg|building|fl|floor|#)\\.?\\s*#?[A-Za-z0-9-]+\\b)?"
        + "(?:(?:,\\s*|\\s+)[A-Za-z][A-Za-z .'-]{1,30}?,?\\s+" + STATES + "\\b(?:\\s+\\d{5}(?:-\\d{4})?)?"
        + "|,?\\s+" + STATES + "\\s+\\d{5}(?:-\\d{4})?"
        + "|\\s+\\d{5}(?:-\\d{4})?(?!\\d))?",
        Pattern.CASE_INSENSITIVE);

    /** Where street addresses appear in a message, as {start, end} pairs. */
    static java.util.List<int[]> addresses(String text) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (text == null || text.length() < 8) return out;
        Matcher m = ADDRESS.matcher(text);
        while (m.find()) out.add(new int[]{m.start(), m.end()});
        return out;
    }

    private static final Pattern MEDIA_EMOJI = Pattern.compile("^(?:\\x{1F4F7}|\\x{1F4F8}|\\x{1F3A5}|\\x{1F3A4}|\\x{1F4CE}|\\x{1F4CD}|\\x{1F5BC}\\x{FE0F}?|\\x{1F39E}\\x{FE0F}?|\\x{1F3B5})\\s*");
    /** "📷 Photo" → "Photo" (Google adds these to some previews). */
    static String withoutMediaEmoji(String text) { return text == null ? "" : MEDIA_EMOJI.matcher(text).replaceFirst(""); }
}
