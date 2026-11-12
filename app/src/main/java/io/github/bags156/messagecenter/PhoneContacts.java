package io.github.bags156.messagecenter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract.CommonDataKinds.Phone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The phone numbers in this phone's own contacts, for starting a conversation. Google
 * Messages only hands a linked device its first few hundred contacts, so this is where most
 * people are found. Read on this phone only, and only with the contacts permission.
 */
final class PhoneContacts {
    static final class Entry {
        final String name, number, formatted, label;
        Entry(String name, String number, String formatted, String label) {
            this.name = name; this.number = number; this.formatted = formatted; this.label = label;
        }
    }

    static boolean allowed(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Every number in the phone's contacts, sorted by name; one entry per contact and number. Call off the main thread. */
    static List<Entry> all(Context context) {
        List<Entry> out = new ArrayList<>();
        if (!allowed(context)) return out;
        String[] columns = {Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.TYPE, Phone.LABEL};
        Set<String> seen = new HashSet<>();
        try (Cursor c = context.getContentResolver().query(Phone.CONTENT_URI, columns, null, null, Phone.SORT_KEY_PRIMARY + " ASC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String name = text(c, 0), raw = text(c, 1), normalized = text(c, 2);
                String number = !normalized.isEmpty() ? normalized : ChatText.normalizeNumber(raw);
                // Short codes and numbers saved without a country code still work as typed.
                if (number.isEmpty()) { String digits = ChatText.digits(raw); if (digits.length() >= 3) number = digits; }
                if (number.isEmpty() || !seen.add(name + "\n" + number)) continue;
                CharSequence label = Phone.getTypeLabel(context.getResources(), c.isNull(3) ? Phone.TYPE_OTHER : c.getInt(3), text(c, 4));
                out.add(new Entry(name.isEmpty() ? raw : name, number, raw, label == null ? "" : label.toString()));
            }
        } catch (Exception ignored) {
            // A contacts app that misbehaves shouldn't stop you from typing a number.
        }
        return out;
    }

    /** The phone's contacts as people, each once with each number once (see ContactPeople). Call off the main thread. */
    static List<ContactPeople.Person> people(Context context) {
        List<ContactPeople.Row> rows = new ArrayList<>();
        if (!allowed(context)) return new ArrayList<>();
        String[] columns = {Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.TYPE, Phone.LABEL};
        try (Cursor c = context.getContentResolver().query(Phone.CONTENT_URI, columns, null, null, Phone.SORT_KEY_PRIMARY + " ASC, " + Phone.IS_SUPER_PRIMARY + " DESC")) {
            if (c == null) return new ArrayList<>();
            while (c.moveToNext()) {
                CharSequence label = Phone.getTypeLabel(context.getResources(), c.isNull(4) ? Phone.TYPE_OTHER : c.getInt(4), text(c, 5));
                rows.add(new ContactPeople.Row(c.isNull(0) ? -1 : c.getLong(0), text(c, 1), text(c, 2), text(c, 3), label == null ? "" : label.toString()));
            }
        } catch (Exception ignored) {
            // A contacts app that misbehaves just means an empty list.
        }
        return ContactPeople.group(rows);
    }

    private static String text(Cursor c, int i) { String s = c.isNull(i) ? null : c.getString(i); return s == null ? "" : s.trim(); }

    private PhoneContacts() {}
}
