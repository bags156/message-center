package io.github.bags156.messagecenter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First names exactly as saved in the phone's contacts ("Hannah Kay" stays "Hannah Kay"), so
 * labels like "Hannah Kay is typing" don't cut a two-word first name. Only used when contacts
 * access was allowed; otherwise Google's first name is used.
 */
final class GivenNames {
    private static volatile Context app;
    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    static void init(Context context) { if (app == null) app = context.getApplicationContext(); }

    /** The contact's given name for this phone number, or "" if unknown or not allowed. */
    static String get(String number) {
        Context c = app;
        if (c == null || number == null || number.isEmpty() || c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return "";
        String hit = cache.get(number);
        if (hit != null) return hit;
        String found = "";
        try (Cursor contact = c.getContentResolver().query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
            if (contact != null && contact.moveToFirst()) {
                long id = contact.getLong(0);
                try (Cursor name = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME},
                        ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(id), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE}, null)) {
                    if (name != null && name.moveToFirst() && name.getString(0) != null) found = name.getString(0).trim();
                }
            }
        } catch (Exception ignored) {}
        cache.put(number, found);
        return found;
    }

    static void forget() { cache.clear(); }
}
