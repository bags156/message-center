package io.github.bags156.messagecenter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * Read-only access to downloaded attachments for the app another app opens or shares them
 * with. Not exported: other apps can only read a file when this app grants that one URI.
 */
public final class MediaProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.bags156.messagecenter.media";

    /** A content:// link for a file in the attachment cache. */
    static Uri uri(File file, String displayName) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.getName())
            .appendQueryParameter("name", displayName == null || displayName.isEmpty() ? file.getName() : displayName).build();
    }

    /** A writable link for one camera photo; only files in the app's outgoing folder can be written. */
    static Uri outgoingUri(File file) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("out").appendPath(file.getName()).build();
    }

    private File resolveOutgoing(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        java.util.List<String> parts = uri.getPathSegments();
        if (context == null || parts.size() != 2 || !"out".equals(parts.get(0))) throw new FileNotFoundException();
        String name = parts.get(1);
        if (name.contains("/") || name.contains("..") || !name.endsWith("-camera.jpg")) throw new FileNotFoundException();
        File dir = new File(context.getCacheDir(), "outgoing");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, name);
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        String name = uri.getLastPathSegment();
        // Only plain file names directly inside the cache folder; never a path.
        if (context == null || name == null || name.contains("/") || name.contains("..") || uri.getPathSegments().size() != 1) throw new FileNotFoundException();
        File file = new File(MediaCache.dir(context), name);
        if (!file.isFile()) throw new FileNotFoundException();
        return file;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri.getPathSegments().size() == 2) {
            // A camera app saving the photo you just took (or reading it back).
            return ParcelFileDescriptor.open(resolveOutgoing(uri), ParcelFileDescriptor.parseMode(mode) | ParcelFileDescriptor.MODE_CREATE);
        }
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        if (uri.getPathSegments().size() == 2) return "image/jpeg";
        String name = uri.getLastPathSegment();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String mime = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return mime == null ? "application/octet-stream" : mime;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File file;
        try { file = resolve(uri); } catch (FileNotFoundException e) { return null; }
        String display = uri.getQueryParameter("name");
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{display == null ? file.getName() : display, file.length()});
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
