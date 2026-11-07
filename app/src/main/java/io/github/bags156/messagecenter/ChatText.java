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

    /** iPhones send a reply to a non-iPhone chat as "Replied to a message:" plus the text, with no link to the original. */
    static String replyFallbackBody(String body) {
        if (body == null) return null;
        Matcher m = REPLY_FALLBACK.matcher(body.trim());
        return m.matches() ? m.group(1).trim() : null;
    }

    /** "Loved an image" style iPhone fallbacks: {emoji, verb, object}, or null. */
    static String[] mediaReaction(String body) {
        if (body == null) return null;
        Matcher m = MEDIA_REACTION.matcher(body.trim());
        if (!m.matches()) return null;
        return new String[]{emoji(m.group(1)), m.group(1).toLowerCase(Locale.ROOT), m.group(2)};
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
     * when the text is an ordinary message. The quoted text is kept; nothing is matched to
     * another message by its wording.
     */
    static String reactionLine(String sender, String body) {
        String[] media = mediaReaction(body);
        if (media != null) return sender + " " + media[1] + " " + media[2] + " " + media[0];
        ReactionParser.Reaction r = ReactionParser.parse(body);
        if (r == null) return null;
        String quote = r.quote.replaceAll("\\s+", " ").trim();
        if (quote.length() > 60) quote = quote.substring(0, 59) + "…";
        return sender + (r.removed ? " removed " + r.emoji + " from “" : " reacted " + r.emoji + " to “") + quote + "”";
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
}
