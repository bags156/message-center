package io.github.bags156.messagecenter;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import org.json.JSONObject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Birthdays from this phone's contacts (the contacts permission, as for names and photos), matched
 * to one-to-one chats by number: a note at the top of their chat in the week before and on the day,
 * and a cake by their name in the list on the day. Read on this phone only.
 */
final class Birthdays {
    private Birthdays() {}

    static final class Birthday {
        final int year, month, day; // year 0 when the contact has no year
        Birthday(int year, int month, int day) { this.year = year; this.month = month; this.day = day; }
    }

    /** A contact's birthday as Android stores it: "1994-07-21", "--07-21" (no year), "19940721", or "07/21/1994". Null if it isn't one. */
    static Birthday parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        java.util.regex.Matcher m;
        if ((m = java.util.regex.Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(t)).find()) return make(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        if ((m = java.util.regex.Pattern.compile("^--(\\d{1,2})-?(\\d{1,2})$").matcher(t)).find()) return make(0, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        if ((m = java.util.regex.Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$").matcher(t)).find()) return make(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        if ((m = java.util.regex.Pattern.compile("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?$").matcher(t)).find()) return make(m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        return null;
    }

    private static Birthday make(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        // Some contacts apps save "no year" as 1604 or 1900.
        if (year == 1604 || year == 1900 || year < 1850) year = 0;
        return new Birthday(year, month, day);
    }

    /** Days until the next birthday (0 today). Feb 29 birthdays fall on Feb 28 in other years. */
    static int daysUntil(Birthday b, LocalDate today) {
        LocalDate next = on(b, today.getYear());
        if (next.isBefore(today)) next = on(b, today.getYear() + 1);
        return (int) (next.toEpochDay() - today.toEpochDay());
    }

    private static LocalDate on(Birthday b, int year) {
        int day = Math.min(b.day, LocalDate.of(year, b.month, 1).lengthOfMonth());
        return LocalDate.of(year, b.month, day);
    }

    /** "Hannah turns 30 today", "Hannah's birthday is tomorrow", "Hannah's birthday is Friday"; null more than a week out. */
    static String label(String first, Birthday b, LocalDate today) {
        int days = daysUntil(b, today);
        if (days > 6) return null;
        String who = first == null || first.isEmpty() ? "Their" : first + "'s";
        LocalDate when = today.plusDays(days);
        int age = b.year > 0 ? when.getYear() - b.year : 0;
        if (days == 0) return age > 0 && age < 120 ? first + " turns " + age + " today" : who + " birthday is today";
        String day = days == 1 ? "tomorrow" : when.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault());
        return age > 0 && age < 120 ? (first == null || first.isEmpty() ? "They" : first) + " turns " + age + " " + (days == 1 ? "tomorrow" : "on " + day) : who + " birthday is " + day;
    }

    // ---- this phone's contacts ----

    private static volatile Map<String, Birthday> byNumber;
    private static volatile long loadedAt;
    private static final ExecutorService loader = Executors.newSingleThreadExecutor();
    private static volatile boolean loading;

    /** The birthday of the person in a one-to-one chat, from what's been read; reads the contacts again in the background now and then. */
    static Birthday forChat(Context context, JSONObject chat) {
        JSONObject other = chat == null ? null : PairedProtocol.other(chat);
        if (other == null || !PhoneContacts.allowed(context)) return null;
        Map<String, Birthday> map = byNumber;
        if ((map == null || System.currentTimeMillis() - loadedAt > 6 * 3_600_000L) && !loading) {
            loading = true; Context app = context.getApplicationContext();
            loader.execute(() -> {
                boolean first = byNumber == null;
                try { byNumber = load(app); loadedAt = System.currentTimeMillis(); } finally { loading = false; }
                Runnable r = loaded; if (first && r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
            });
        }
        return map == null ? null : map.get(ContactSearch.key(PairedProtocol.number(other)));
    }

    /** Runs on the main thread once the contacts' birthdays are first read (the screen redraws with them). */
    static volatile Runnable loaded;

    /** Whether the birthdays are read (or can't be); until then, rows drawn without a cake are drawn again once they are. */
    static boolean ready() { return byNumber != null; }

    private static Map<String, Birthday> load(Context c) {
        Map<String, Birthday> out = new HashMap<>();
        Map<Long, Birthday> byContact = new HashMap<>();
        String[] cols = {ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Event.START_DATE};
        String where = ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.CommonDataKinds.Event.TYPE + "=?";
        String[] args = {ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, String.valueOf(ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)};
        try (Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, cols, where, args, null)) {
            while (cur != null && cur.moveToNext()) { Birthday b = parse(cur.getString(1)); if (b != null) byContact.put(cur.getLong(0), b); }
        } catch (Exception ignored) {}
        if (byContact.isEmpty()) return out;
        String[] phone = {ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, phone, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                Birthday b = byContact.get(cur.getLong(0)); String key = ContactSearch.key(cur.getString(1));
                if (b != null && key.length() >= 7) out.put(key, b);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Device checks only. */
    static void setForChecks(Map<String, Birthday> map) { byNumber = map; loadedAt = System.currentTimeMillis(); }
}
