package io.github.bags156.messagecenter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * "Create task" on a message: the message goes to your task app (Google Tasks, Todoist, TickTick,
 * Microsoft To Do and others) as a new task, the way sharing to it would, with who sent it and when.
 * The app you pick is remembered (Settings › Messages › Tasks to change it). Nothing is sent anywhere
 * except to that app, on this phone.
 */
final class TaskApps {
    /** Known task apps, in the order they're offered. Others whose name says tasks, to-dos or reminders are offered after. */
    static final List<String> KNOWN = Arrays.asList(
        "com.google.android.apps.tasks", "com.todoist", "com.ticktick.task", "com.microsoft.todos", "org.tasks",
        "com.anydo", "com.google.android.keep", "com.coloros.note", "com.oneplus.note", "com.samsung.android.app.reminder");
    private static final String KEY = "task_app";

    /** One task app that can take a new task. */
    static final class App {
        final ComponentName component; final String label;
        App(ComponentName component, String label) { this.component = component; this.label = label; }
    }

    /** Whether a share target looks like a task app: a known one, or one named for tasks, to-dos or reminders. */
    static boolean taskLike(String pkg, String label) {
        if (KNOWN.contains(pkg)) return true;
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT);
        return l.contains("task") || l.contains("to do") || l.contains("to-do") || l.contains("todo") || l.contains("reminder");
    }

    /** Installed task apps that take shared text, known ones first. */
    static List<App> installed(Context c) { return apps(c, true); }
    /** Every app that takes shared text, for a task app this doesn't recognize. */
    static List<App> anyApp(Context c) { return apps(c, false); }
    private static List<App> apps(Context c, boolean tasksOnly) {
        PackageManager pm = c.getPackageManager(); List<App> out = new ArrayList<>();
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain");
        for (ResolveInfo r : pm.queryIntentActivities(send, 0)) {
            String pkg = r.activityInfo.packageName; if (pkg.equals(c.getPackageName())) continue;
            String label = String.valueOf(r.loadLabel(pm)); String app = String.valueOf(r.activityInfo.applicationInfo.loadLabel(pm));
            if (tasksOnly && !taskLike(pkg, label) && !taskLike(pkg, app)) continue;
            // An app with a share target named for its action ("Add to Tasks") keeps that; otherwise the app's name.
            out.add(new App(new ComponentName(pkg, r.activityInfo.name), label.equalsIgnoreCase(app) || label.isEmpty() ? app : app + " · " + label));
        }
        if (tasksOnly) out.sort((a, b) -> rank(a.component.getPackageName()) - rank(b.component.getPackageName()));
        else out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }
    private static int rank(String pkg) { int i = KNOWN.indexOf(pkg); return i < 0 ? KNOWN.size() : i; }

    /** The task app you picked, if it's still installed and still takes tasks; otherwise null. */
    static App chosen(Context c) {
        String saved = prefs(c).getString(KEY, "");
        if (saved.isEmpty()) return null;
        for (App a : anyApp(c)) if (a.component.flattenToString().equals(saved)) return a;
        return null;
    }
    static void choose(Context c, App a) { prefs(c).edit().putString(KEY, a == null ? "" : a.component.flattenToString()).apply(); }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("paired", Context.MODE_PRIVATE); }

    /**
     * The new task: its title (the message's first line, or what was sent if it's only a photo, video
     * or file) and its notes (the whole message, then who sent it and when).
     */
    static String[] task(String text, String what, String sender, String when) {
        String body = text == null ? "" : text.trim(), title = body.isEmpty() ? what + (sender.isEmpty() ? "" : " from " + sender) : body.split("\\R", 2)[0].trim();
        if (title.codePointCount(0, title.length()) > 100) title = title.substring(0, title.offsetByCodePoints(0, 99)).trim() + "…";
        String from = sender.isEmpty() ? when : sender + (when.isEmpty() ? "" : ", " + when);
        String notes = (body.isEmpty() ? "" : body + "\n\n") + "— " + from + " (Message Center)";
        return new String[]{title, notes};
    }

    /** Hands the task to the app: a share of text, which task apps turn into a new task. */
    static Intent intent(App app, String[] task) {
        return new Intent(Intent.ACTION_SEND).setType("text/plain").setComponent(app.component)
            .putExtra(Intent.EXTRA_SUBJECT, task[0]).putExtra(Intent.EXTRA_TITLE, task[0])
            .putExtra(Intent.EXTRA_TEXT, task[0].equals(task[1].split("\n", 2)[0]) ? task[1] : task[0] + "\n\n" + task[1]);
    }

    private TaskApps() {}
}
