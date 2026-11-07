package io.github.bags156.messagecenter;

import android.content.Context;
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

/** Contact photos from Google, cached in private app storage by participant ID. */
final class Avatars {
    private Avatars() {}
    static File dir(Context context) { return new File(context.getFilesDir(), "avatars"); }
    static File file(Context context, String participantID) { return new File(dir(context), hex(participantID) + ".img"); }
    static String hex(String id) {
        StringBuilder out = new StringBuilder();
        for (byte b : id.getBytes(StandardCharsets.UTF_8)) out.append(String.format("%02x", b));
        return out.toString();
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
        Bitmap scaled = Bitmap.createScaledBitmap(source, size, size, true);
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
            int side = Math.min(photo.getWidth(), photo.getHeight());
            Bitmap square = Bitmap.createBitmap(photo, (photo.getWidth() - side) / 2, (photo.getHeight() - side) / 2, side, side);
            canvas.drawBitmap(Bitmap.createScaledBitmap(square, size, size, true), 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
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
    }
}
