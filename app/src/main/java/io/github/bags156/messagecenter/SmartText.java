package io.github.bags156.messagecenter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Things worth tapping in a message (package tracking numbers, flights, dates and times) and
 * a few sender/keyword rules. Pure Java so every rule is unit tested.
 */
final class SmartText {
    private SmartText() {}

    /** A tappable piece of a message: where it is, what kind, and what it means. */
    static final class Hit {
        final int start, end; final String kind, value; final long time;
        Hit(int start, int end, String kind, String value, long time) { this.start = start; this.end = end; this.kind = kind; this.value = value; this.time = time; }
    }

    // ---- Package tracking ----
    private static final Pattern UPS = Pattern.compile("(?<![A-Za-z0-9])1Z[0-9A-Z]{16}(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMAZON = Pattern.compile("(?<![A-Za-z0-9])TBA\\d{12}(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern USPS = Pattern.compile("(?<![A-Za-z0-9])(?:9[1-5]\\d{20,24}|[A-Z]{2}\\d{9}US)(?![A-Za-z0-9])");
    private static final Pattern DIGITS_12_15 = Pattern.compile("(?<![A-Za-z0-9$.,:/-])(\\d{12}|\\d{15})(?![A-Za-z0-9])");
    private static final Pattern FEDEX_CONTEXT = Pattern.compile("(?i)\\bfed\\s?ex\\b");

    static List<Hit> tracking(String text) {
        List<Hit> out = new ArrayList<>();
        if (text == null) return out;
        add(out, UPS.matcher(text), "ups");
        add(out, AMAZON.matcher(text), "amazon");
        add(out, USPS.matcher(text), "usps");
        if (FEDEX_CONTEXT.matcher(text).find()) add(out, DIGITS_12_15.matcher(text), "fedex");
        return out;
    }
    private static void add(List<Hit> out, Matcher m, String kind) {
        while (m.find()) out.add(new Hit(m.start(), m.end(), kind, m.group().toUpperCase(Locale.ROOT), 0));
    }
    static String trackingUrl(Hit h) {
        switch (h.kind) {
            case "ups": return "https://www.ups.com/track?tracknum=" + h.value;
            case "amazon": return "https://track.amazon.com/tracking/" + h.value;
            case "usps": return "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + h.value;
            case "fedex": return "https://www.fedex.com/fedextrack/?trknbr=" + h.value;
            default: return "";
        }
    }

    // ---- Flights ----
    private static final String AIRLINES = "AA|DL|UA|WN|AS|B6|NK|F9|G4|HA|SY|MX|XP|AC|WS|PD|AM|Y4|VB|BA|VS|LH|AF|KL|IB|EI|LX|OS|SK|AY|TP|EK|QR|EY|TK|SQ|CX|NH|JL|KE|QF|NZ|LA|AV|CM";
    private static final Pattern FLIGHT = Pattern.compile("(?<![A-Za-z0-9])(" + AIRLINES + ")\\s?(\\d{1,4})(?![A-Za-z0-9])");
    private static final Pattern FLIGHT_CONTEXT = Pattern.compile("(?i)\\b(flight|flights|flying|fly|flies|plane|landing|lands|landed|departs|departing|depart|boarding|gate|airport|layover|arrives|arriving|delayed)\\b");

    /** "AA 1234" or "DL123" when the message is about flying (so "AR 72712" or "UA 5" in passing aren't flights). */
    static List<Hit> flights(String text) {
        List<Hit> out = new ArrayList<>();
        if (text == null || !FLIGHT_CONTEXT.matcher(text).find()) return out;
        Matcher m = FLIGHT.matcher(text);
        while (m.find()) out.add(new Hit(m.start(), m.end(), "flight", m.group(1) + " " + m.group(2), 0));
        return out;
    }
    static String flightUrl(Hit h) { return "https://www.google.com/search?q=" + h.value.replace(' ', '+') + "+flight+status"; }

    // ---- Dates and times ----
    private static final String DAY = "(?:mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)";
    private static final String MONTH = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?";
    private static final String CLOCK = "(?:(?:at|@)\\s*)?(?:(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|a|p)\\b|(noon|midnight))";
    private static final String CLOCK_AT = "(?:at|@)\\s*(\\d{1,2})(?::(\\d{2}))?(?!\\s*(?:am|pm|a\\.m|p\\.m|%|\\d|:|/|-|\\.\\d))";
    /** A day (today/tonight/tomorrow, a weekday, a month and day, or m/d) followed by a time, or a time followed by a day. */
    private static final Pattern WHEN = Pattern.compile(
        "(?<![\\w])(?:(today|tonight|tomorrow|tmrw|tmr)|(?:(?:this|next)\\s+)?(" + DAY + ")|(" + MONTH + ")\\s+(\\d{1,2})(?:st|nd|rd|th)?|(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?)"
        + "(?:\\s*,)?\\s+(?:" + CLOCK + "|" + CLOCK_AT + ")"
        + "|(?<![\\w:])" + CLOCK + "\\s+(?:on\\s+)?(today|tonight|tomorrow|tmrw|tmr|(?:this\\s+|next\\s+)?" + DAY + ")\\b",
        Pattern.CASE_INSENSITIVE);

    /** Dates with a time ("Friday at 6", "tomorrow 7:30pm", "Nov 12 at 3", "6pm tonight"), resolved to local time after {@code now}. */
    static List<Hit> dateTimes(String text, long now, TimeZone zone) {
        List<Hit> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = WHEN.matcher(text);
        while (m.find()) {
            long t = resolve(m, now, zone);
            if (t > 0) out.add(new Hit(m.start(), m.end(), "date", m.group().trim(), t));
        }
        return out;
    }

    // Groups: 1 today/tonight/tomorrow, 2 weekday, 3 month, 4 day of month, 5-7 m/d/y; time after the day:
    // 8 hour, 9 minute, 10 am/pm, 11 noon/midnight, or "at" form 12 hour, 13 minute. Time before the day:
    // 14 hour, 15 minute, 16 am/pm, 17 noon/midnight, 18 the day.
    private static long resolve(Matcher m, long now, TimeZone zone) {
        String rel = null, day = null, month = null, dom = null, num1 = null, num2 = null, year = null, hour, minute, ampm, word;
        if (m.group(18) != null) {
            hour = m.group(14); minute = m.group(15); ampm = m.group(16); word = m.group(17);
            String d = m.group(18).toLowerCase(Locale.ROOT).replaceAll("^(this|next)\\s+", "");
            if (d.matches("today|tonight|tomorrow|tmrw|tmr")) rel = d; else day = d;
        } else {
            rel = m.group(1); day = m.group(2); month = m.group(3); dom = m.group(4); num1 = m.group(5); num2 = m.group(6); year = m.group(7);
            if (m.group(8) != null || m.group(11) != null) { hour = m.group(8); minute = m.group(9); ampm = m.group(10); word = m.group(11); }
            else { hour = m.group(12); minute = m.group(13); ampm = null; word = null; }
        }
        Calendar c = Calendar.getInstance(zone); c.setTimeInMillis(now);
        Calendar at = (Calendar) c.clone(); at.set(Calendar.SECOND, 0); at.set(Calendar.MILLISECOND, 0);
        int h, min = 0;
        if (word != null) h = word.equalsIgnoreCase("noon") ? 12 : 0;
        else {
            if (hour == null) return -1;
            h = Integer.parseInt(hour); if (minute != null) min = Integer.parseInt(minute);
            if (h > 23 || min > 59) return -1;
            String ap = ampm == null ? "" : ampm.toLowerCase(Locale.ROOT).replace(".", "");
            if (ap.startsWith("p") && h < 12) h += 12; else if (ap.startsWith("a") && h == 12) h = 0;
            else if (ap.isEmpty()) {
                if (h > 12 || h == 0) { /* 24-hour clock */ }
                else if (rel != null && rel.equalsIgnoreCase("tonight") && h < 12) h += 12;
                else if (h >= 1 && h <= 7) h += 12; // "at 6" means evening
            }
        }
        if (rel != null) {
            String r = rel.toLowerCase(Locale.ROOT);
            if (r.startsWith("tom") || r.startsWith("tm")) at.add(Calendar.DAY_OF_YEAR, 1);
            else if (r.equals("tonight") && h < 12 && word == null) h += 12;
        } else if (day != null) {
            int want = weekday(day.toLowerCase(Locale.ROOT).replaceAll("^(this|next)\\s+", ""));
            int diff = (want - at.get(Calendar.DAY_OF_WEEK) + 7) % 7;
            at.add(Calendar.DAY_OF_YEAR, diff);
            at.set(Calendar.HOUR_OF_DAY, h); at.set(Calendar.MINUTE, min);
            if (at.getTimeInMillis() <= now) at.add(Calendar.DAY_OF_YEAR, 7);
            return at.getTimeInMillis();
        } else if (month != null) {
            int mo = month(month.toLowerCase(Locale.ROOT)), d = Integer.parseInt(dom);
            if (d < 1 || d > 31) return -1;
            at.set(Calendar.MONTH, mo); at.set(Calendar.DAY_OF_MONTH, d);
            at.set(Calendar.HOUR_OF_DAY, h); at.set(Calendar.MINUTE, min);
            if (at.getTimeInMillis() < now - 24L * 3600_000) at.add(Calendar.YEAR, 1);
            return at.getTimeInMillis();
        } else if (num1 != null) {
            int mo = Integer.parseInt(num1) - 1, d = Integer.parseInt(num2);
            if (mo < 0 || mo > 11 || d < 1 || d > 31) return -1;
            at.set(Calendar.MONTH, mo); at.set(Calendar.DAY_OF_MONTH, d);
            if (year != null) { int y = Integer.parseInt(year); at.set(Calendar.YEAR, y < 100 ? 2000 + y : y); }
            at.set(Calendar.HOUR_OF_DAY, h); at.set(Calendar.MINUTE, min);
            if (year == null && at.getTimeInMillis() < now - 24L * 3600_000) at.add(Calendar.YEAR, 1);
            return at.getTimeInMillis();
        }
        at.set(Calendar.HOUR_OF_DAY, h); at.set(Calendar.MINUTE, min);
        return at.getTimeInMillis();
    }
    private static int weekday(String d) {
        if (d.startsWith("mon")) return Calendar.MONDAY; if (d.startsWith("tue")) return Calendar.TUESDAY; if (d.startsWith("wed")) return Calendar.WEDNESDAY;
        if (d.startsWith("thu")) return Calendar.THURSDAY; if (d.startsWith("fri")) return Calendar.FRIDAY; if (d.startsWith("sat")) return Calendar.SATURDAY; return Calendar.SUNDAY;
    }
    private static int month(String m) {
        String[] names = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        for (int i = 0; i < 12; i++) if (m.startsWith(names[i])) return i;
        return 0;
    }

    /** Everything tappable in one message, without overlaps (earlier and longer win). */
    static List<Hit> all(String text, long now, TimeZone zone) {
        List<Hit> found = new ArrayList<>();
        found.addAll(tracking(text)); found.addAll(flights(text)); found.addAll(dateTimes(text, now, zone));
        found.sort((a, b) -> a.start != b.start ? Integer.compare(a.start, b.start) : Integer.compare(b.end - b.start, a.end - a.start));
        List<Hit> out = new ArrayList<>(); int end = -1;
        for (Hit h : found) if (h.start >= end) { out.add(h); end = h.end; }
        return out;
    }

    // ---- Senders ----
    /** A short code (5–6 digits) or a business name instead of a phone number: how promotions and alerts arrive. */
    static boolean shortCodeSender(String number) {
        if (number == null) return false;
        String n = number.trim();
        if (n.isEmpty()) return false;
        if (n.matches("\\d{3,6}")) return true;
        return n.matches(".*[A-Za-z].*") && !n.contains("@");
    }

    // ---- Keywords ----
    /** The first keyword that appears as a whole word or phrase (any case), or "". */
    static String keyword(String text, List<String> keywords) {
        if (text == null || keywords == null) return "";
        for (String k : keywords) {
            if (k == null || k.trim().length() < 2) continue;
            if (Pattern.compile("(?<![\\w])" + Pattern.quote(k.trim()) + "(?![\\w])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find()) return k.trim();
        }
        return "";
    }
    static List<String> splitList(String saved) {
        List<String> out = new ArrayList<>();
        if (saved == null) return out;
        for (String s : saved.split("[,\\n]")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    // ---- Search filters ----
    /** "from:hannah has:photo pizza" → text "pizza", from "hannah", photo true. */
    static final class Search {
        String text = "", from = ""; boolean photo, link; int days;
    }
    static Search parseSearch(String query) {
        Search s = new Search();
        StringBuilder rest = new StringBuilder();
        Matcher m = Pattern.compile("(?i)(from:(\"[^\"]+\"|\\S+)|has:photos?|has:links?|last:(\\d{1,3})d|\\S+)").matcher(query == null ? "" : query);
        while (m.find()) {
            String t = m.group(1), lower = t.toLowerCase(Locale.ROOT);
            if (lower.startsWith("from:")) s.from = m.group(2).replace("\"", "");
            else if (lower.startsWith("has:photo")) s.photo = true;
            else if (lower.startsWith("has:link")) s.link = true;
            else if (lower.startsWith("last:")) s.days = Integer.parseInt(m.group(3));
            else { if (rest.length() > 0) rest.append(' '); rest.append(t); }
        }
        s.text = rest.toString();
        return s;
    }
    static boolean hasFilters(Search s) { return !s.from.isEmpty() || s.photo || s.link || s.days > 0; }
}
