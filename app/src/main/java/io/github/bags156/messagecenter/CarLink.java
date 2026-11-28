package io.github.bags156.messagecenter;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * Whether the phone is connected to Android Auto right now, as Android Auto itself reports it (the
 * status androidx.car.app's CarConnection reads from the car host's provider). Unknown counts as not
 * connected. For checks, connectedForTests overrides it.
 */
final class CarLink {
    private static final Uri HOST = Uri.parse("content://androidx.car.app.connection");
    /** CarConnection.CAR_CONNECTION_STATE; 0 is not connected, 1 a car's own Android (Automotive), 2 Android Auto. */
    static final String STATE = "CarConnectionState";
    static volatile Boolean connectedForTests;

    static boolean connected(Context c) {
        Boolean forced = connectedForTests; if (forced != null) return forced;
        try (Cursor q = c.getContentResolver().query(HOST, new String[]{STATE}, null, null, null)) {
            if (q == null || !q.moveToFirst()) return false;
            int column = q.getColumnIndex(STATE);
            return column >= 0 && q.getInt(column) != 0;
        } catch (Exception e) { return false; }
    }

    private CarLink() {}
}
