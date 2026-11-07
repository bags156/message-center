package io.github.bags156.messagecenter;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.ContactsContract;
import java.io.InputStream;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Contact photos, cached in private app storage by participant ID: Google's thumbnail, and the full-size photo from the phone's own contacts when allowed. */
final class Avatars {
    private Avatars() {}
    static File dir(Context context) { return new File(context.getFilesDir(), "avatars"); }
    static File file(Context context, String participantID) { return new File(dir(context), hex(participantID) + ".img"); }
    static String hex(String id) {
        StringBuilder out = new StringBuilder();
        for (byte b : id.getBytes(StandardCharsets.UTF_8)) out.append(String.format("%02x", b));
        return out.toString();
    }
    /** The full-size photo from this phone's contacts, when one was found. */
    static File phoneFile(Context context, String participantID) { return new File(dir(context), hex(participantID) + ".hd.jpg"); }
    /** The sharpest photo on hand for a person: the phone's contact photo, else Google's thumbnail. */
    static File best(Context context, String participantID) { File hd = phoneFile(context, participantID); return hd.isFile() ? hd : file(context, participantID); }
    static boolean canUsePhonePhotos(Context context) { return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED; }
    /**
     * Looks up this number in the phone's contacts and keeps a copy of its full-size photo
     * (Google only sends a small thumbnail). Checked at most once a day per person.
     * Returns true if the saved photo changed.
     */
    static boolean fromPhone(Context context, String participantID, String number) {
        if (participantID.isEmpty() || number.isEmpty() || !canUsePhonePhotos(context)) return false;
        SharedPreferences prefs = context.getSharedPreferences("avatars", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("checked:" + participantID, 0) < 24 * 60 * 60 * 1000L) return false;
        prefs.edit().putLong("checked:" + participantID, now).apply();
        ContentResolver resolver = context.getContentResolver();
        File hd = phoneFile(context, participantID);
        Uri contact = null;
        try (Cursor c = resolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                new String[]{ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY}, null, null, null)) {
            if (c != null && c.moveToFirst()) contact = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1));
        } catch (Exception e) { return false; }
        if (contact == null) return hd.delete();
        try (InputStream in = ContactsContract.Contacts.openContactPhotoInputStream(resolver, contact, true)) {
            if (in == null) return hd.delete();
            Bitmap photo = BitmapFactory.decodeStream(in);
            if (photo == null) return false;
            int longest = Math.max(photo.getWidth(), photo.getHeight());
            if (longest > 640) { float k = 640f / longest; photo = Bitmap.createScaledBitmap(photo, Math.round(photo.getWidth() * k), Math.round(photo.getHeight() * k), true); }
            File dir = dir(context);
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File temp = new File(dir, hd.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) { photo.compress(Bitmap.CompressFormat.JPEG, 92, out); }
            if (!temp.renameTo(hd)) { temp.delete(); return false; }
            return true;
        } catch (Exception e) { return false; }
    }
    /** Shrinks in halves first, so a large photo stays smooth instead of grainy. */
    static Bitmap scaleSquare(Bitmap photo, int size) {
        int side = Math.min(photo.getWidth(), photo.getHeight());
        Bitmap b = Bitmap.createBitmap(photo, (photo.getWidth() - side) / 2, (photo.getHeight() - side) / 2, side, side);
        while (b.getWidth() / 2 >= size) b = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
        return b.getWidth() == size ? b : Bitmap.createScaledBitmap(b, size, size, true);
    }
    static void save(Context context, String participantID, byte[] image) throws IOException {
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Photo cache unavailable");
        File target = file(context, participantID), temp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(image); }
        if (!temp.renameTo(target)) { temp.delete(); throw new IOException("Photo not saved"); }
    }
    private static final int[] FALLBACK = {0xff1a73e8, 0xff188038, 0xffe37400, 0xffa142f4, 0xffd93025, 0xff007b83, 0xff9334e6, 0xffc5221f};
    /** A stable color per name for people without a photo or a Google-supplied color. */
    static int fallbackColor(String name) { return FALLBACK[Math.floorMod((name == null ? "" : name).hashCode(), FALLBACK.length)]; }
    /** A square photo cropped to a circle, for notifications. */
    static Bitmap circle(Bitmap source, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Bitmap scaled = scaleSquare(source, size); // crop to a square first; never stretch
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return out;
    }
    /** A colored circle with a white initial, matching the in-app avatars. */
    static Bitmap initial(String letter, int color, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG); fill.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG); text.setColor(Color.WHITE); text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size * 0.45f); text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        canvas.drawText(letter, size / 2f, size / 2f - (text.descent() + text.ascent()) / 2f, text);
        return out;
    }
    /**
     * Full-bleed square for Android's adaptive icons (bubbles, shortcuts). Android masks it to
     * a circle that shows roughly the middle two thirds, so the initial is drawn smaller.
     */
    static Bitmap adaptive(Bitmap photo, String letter, int color, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (photo != null) {
            // Android shows only the middle two-thirds of an adaptive icon. Drawing the photo across
            // the whole square cropped and enlarged it 1.5x (soft and "zoomed"). Instead the whole
            // photo goes in the visible middle at full sharpness, over a copy that fills the edges
            // the system uses for its animations.
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            int visible = Math.round(size * 0.68f), inset = (size - visible) / 2;
            canvas.drawBitmap(scaleSquare(photo, Math.max(1, size / 4)), null, new Rect(0, 0, size, size), paint);
            canvas.drawBitmap(scaleSquare(photo, visible), null, new RectF(inset, inset, inset + visible, inset + visible), paint);
            return out;
        }
        canvas.drawColor(color);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG); text.setColor(Color.WHITE); text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size * 0.3f); text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        canvas.drawText(letter, size / 2f, size / 2f - (text.descent() + text.ascent()) / 2f, text);
        return out;
    }
    static void clear(Context context) {
        File[] files = dir(context).listFiles();
        if (files != null) for (File f : files) f.delete();
        context.getSharedPreferences("avatars", Context.MODE_PRIVATE).edit().clear().apply();
    }
}
