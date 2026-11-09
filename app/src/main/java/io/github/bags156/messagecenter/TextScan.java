package io.github.bags156.messagecenter;

import android.text.SpannableString;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.LruCache;
import java.util.List;
import java.util.TimeZone;

/**
 * What's tappable in a message's text: web links, emails and phone numbers (Android's Linkify),
 * street addresses, and smart bits like tracking numbers, flights and "Friday at 6". Working
 * these out is slow (phone numbers especially), and a chat redraws often, so each text is
 * scanned once and the result reused.
 */
final class TextScan {
    final int[][] linkRanges;
    final String[] linkUrls;
    final List<int[]> places;
    final List<SmartText.Hit> smart;

    private TextScan(int[][] linkRanges, String[] linkUrls, List<int[]> places, List<SmartText.Hit> smart) {
        this.linkRanges = linkRanges; this.linkUrls = linkUrls; this.places = places; this.smart = smart;
    }

    private static final LruCache<String, TextScan> scans = new LruCache<>(800);
    private static final LruCache<String, String> codes = new LruCache<>(800);

    /**
     * The scan of {@code text} for a message sent at {@code sentAt}. Relative dates ("Friday")
     * depend on the day it was sent and the time zone, so those are part of the key.
     */
    static TextScan of(String text, long sentAt) {
        TimeZone zone = TimeZone.getDefault();
        String key = (sentAt + zone.getOffset(sentAt)) / 86_400_000L + "|" + zone.getID() + "|" + text;
        TextScan hit = scans.get(key);
        if (hit != null) return hit;
        SpannableString s = new SpannableString(text);
        int[][] ranges = new int[0][];
        String[] urls = new String[0];
        if (Linkify.addLinks(s, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS)) {
            URLSpan[] spans = s.getSpans(0, s.length(), URLSpan.class);
            ranges = new int[spans.length][];
            urls = new String[spans.length];
            for (int i = 0; i < spans.length; i++) {
                ranges[i] = new int[]{s.getSpanStart(spans[i]), s.getSpanEnd(spans[i])};
                urls[i] = spans[i].getURL();
            }
        }
        TextScan scan = new TextScan(ranges, urls, ChatText.addresses(text), SmartText.all(text, sentAt, zone));
        scans.put(key, scan);
        return scan;
    }

    /** Forgets every scan (the on-device test uses this to time redraws as they were before caching). */
    static void clear() { scans.evictAll(); codes.evictAll(); }

    /** A login or verification code in the text, or "" (cached; see ChatText.oneTimeCode). */
    static String code(String text) {
        String hit = codes.get(text);
        if (hit != null) return hit;
        String code = ChatText.oneTimeCode(text);
        codes.put(text, code);
        return code;
    }
}
