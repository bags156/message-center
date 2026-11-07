package io.github.bags156.messagecenter;

import android.content.Context;
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
    static void clear(Context context) {
        File[] files = dir(context).listFiles();
        if (files != null) for (File f : files) f.delete();
    }
}
