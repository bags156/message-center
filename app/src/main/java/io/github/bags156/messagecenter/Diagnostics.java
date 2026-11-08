package io.github.bags156.messagecenter;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A short journal of what went wrong (connection changes, pictures or videos that wouldn't
 * open, crashes), and "Report a problem", which bundles it with the app's log into one file
 * to send. Never records message text.
 */
final class Diagnostics {
    private static final int KEEP = 300;
    private static volatile Context app;
    private static volatile File lastBadFile;

    static void init(Context context) {
        if (app != null) return;
        app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            java.io.StringWriter w = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(w));
            note("CRASH on " + thread.getName() + ": " + w);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Adds a line to the journal (kept to the last few hundred). */
    static synchronized void note(String line) {
        Context c = app; if (c == null) return;
        try {
            File f = new File(c.getFilesDir(), "diagnostics.txt");
            List<String> lines = new ArrayList<>();
            if (f.isFile()) lines.addAll(java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
            lines.add(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + line.replace('\n', ' '));
            if (lines.size() > KEEP) lines = lines.subList(lines.size() - KEEP, lines.size());
            java.nio.file.Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    /** Remembers a downloaded file that wouldn't open, so a report can include it. */
    static void badFile(File file, String why) { lastBadFile = file; note("Couldn't open " + file.getName() + ": " + why); }
    static File lastBadFile() { File f = lastBadFile; return f != null && f.isFile() ? f : null; }

    /** Builds report.zip: details, the journal, this app's recent system log, and (if asked) the file that wouldn't open. */
    static File buildReport(Context context, String description, String details, boolean includeFile) throws Exception {
        File out = new File(MediaCache.dir(context), "message-center-report-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            String about = "What happened: " + (description.isEmpty() ? "(not described)" : description) + "\n\n"
                + "Message Center " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "Phone: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " + Build.DISPLAY + "\n"
                + details + "\n";
            put(zip, "about.txt", about.getBytes(StandardCharsets.UTF_8));
            File journal = new File(context.getFilesDir(), "diagnostics.txt");
            if (journal.isFile()) put(zip, "journal.txt", java.nio.file.Files.readAllBytes(journal.toPath()));
            put(zip, "log.txt", log());
            File bad = includeFile ? lastBadFile() : null;
            if (bad != null && bad.length() <= (25L << 20)) put(zip, "file-that-would-not-open-" + bad.getName(), java.nio.file.Files.readAllBytes(bad.toPath()));
        }
        return out;
    }

    /** The last part of this app's own system log (apps can read only their own lines). */
    private static byte[] log() {
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "1500", "--pid=" + android.os.Process.myPid()).redirectErrorStream(true).start();
            try (InputStream in = p.getInputStream()) { return in.readAllBytes(); }
        } catch (Exception e) { return ("Couldn't read the log: " + e).getBytes(StandardCharsets.UTF_8); }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry();
    }
}
