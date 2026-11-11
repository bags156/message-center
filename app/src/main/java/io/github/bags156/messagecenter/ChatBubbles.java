package io.github.bags156.messagecenter;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a chat's bubble out of the way while that same chat is open in the full app. Opening the
 * chat takes its bubble down; leaving it puts the bubble back, quietly and as already seen. A
 * chat that had no bubble is never given one. New messages in the chat on screen don't notify
 * at all (PairedService checks {@link #onScreen()}), since you're already looking at them.
 */
final class ChatBubbles {
    private ChatBubbles() {}

    /** Long enough that a quick trip away (a photo picker, turning the phone) doesn't flash the bubble. */
    static final long RESTORE_DELAY_MS = 1200;
    private static final String PREFS = "chat_bubbles", HIDDEN = "hidden", BOOT = "boot";

    private static volatile String onScreen = "";
    /** Bubbles taken down, by chat, so they come back exactly as they were. Main thread only. */
    private static final Map<String, Taken> taken = new HashMap<>();
    private static Handler main;
    private static Context app;
    private static final Runnable restore = ChatBubbles::restoreNow;

    private static final class Taken {
        final String tag; final int id; final Notification notification;
        Taken(String tag, int id, Notification notification) { this.tag = tag; this.id = id; this.notification = notification; }
    }

    /** The chat open in the full app, or "" when none is (or the app isn't on screen). */
    static String onScreen() { return onScreen; }

    /** The full app reports the chat it's showing ("" for none). Call on the main thread. */
    static void showing(Context context, String chat) {
        app = context.getApplicationContext();
        onScreen = chat == null ? "" : chat;
        main().removeCallbacks(restore);
        if (!onScreen.isEmpty()) takeDown(onScreen);
        // Any other chat whose bubble was taken down gets it back after a moment.
        main().postDelayed(restore, RESTORE_DELAY_MS);
    }

    /** After a restart of the app (its bubbles were taken down when it stopped), puts them back. */
    static void restoreSoon(Context context) {
        if (app == null) app = context.getApplicationContext();
        main().removeCallbacks(restore);
        main().postDelayed(restore, RESTORE_DELAY_MS);
    }

    private static Handler main() { if (main == null) main = new Handler(Looper.getMainLooper()); return main; }

    private static void takeDown(String chat) {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        try {
            for (StatusBarNotification n : nm.getActiveNotifications()) {
                if (!("chat:" + chat).equals(n.getTag()) || (n.getNotification().flags & Notification.FLAG_BUBBLE) == 0) continue;
                taken.put(chat, new Taken(n.getTag(), n.getId(), n.getNotification()));
                remember(chat, true);
                nm.cancel(n.getTag(), n.getId());
                Diagnostics.note("Bubble put away while its chat is open");
                return;
            }
        } catch (Exception e) { Diagnostics.note("Couldn't put the bubble away: " + e); }
    }

    private static void restoreNow() {
        if (app == null) return;
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> waiting = new HashSet<>(prefs.getStringSet(HIDDEN, new HashSet<>()));
        waiting.addAll(taken.keySet());
        if (waiting.isEmpty()) return;
        // Android clears bubbles when the phone restarts, so there's nothing to put back.
        if (prefs.getInt(BOOT, -1) != bootCount(app)) { taken.clear(); prefs.edit().clear().apply(); return; }
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        Set<String> active = new HashSet<>();
        try { for (StatusBarNotification n : nm.getActiveNotifications()) if (n.getTag() != null && n.getTag().startsWith("chat:")) active.add(n.getTag().substring(5)); }
        catch (Exception ignored) {}
        for (String chat : toRestore(waiting, onScreen, active)) {
            Taken t = taken.remove(chat);
            boolean done;
            if (t != null) {
                try { nm.notify(t.tag, t.id, seen(app, chat, t.notification)); } catch (Exception e) { Diagnostics.note("Couldn't put the bubble back: " + e); }
                done = true;
            } else done = PairedService.restoreBubble(chat); // the app was closed in between: rebuild it
            if (done) remember(chat, false);
        }
        // A newer notification for the chat already brought its bubble back (or a bubble was made since).
        for (String chat : waiting) if (!chat.equals(onScreen) && active.contains(chat)) { taken.remove(chat); remember(chat, false); }
    }

    /** Which taken-down bubbles to put back: not the chat on screen, and not one that's already showing again. */
    static List<String> toRestore(Collection<String> waiting, String onScreen, Set<String> active) {
        List<String> out = new ArrayList<>();
        for (String chat : waiting) if (!chat.equals(onScreen) && !active.contains(chat)) out.add(chat);
        return out;
    }

    /** The same notification, collapsed, without a sound or a dot, and out of the shade (you've read it). */
    private static Notification seen(Context context, String chat, Notification old) {
        Notification.Builder b = Notification.Builder.recoverBuilder(context, old).setOnlyAlertOnce(true)
                .setGroup("quiet-" + chat).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        Notification.BubbleMetadata meta = old.getBubbleMetadata();
        if (meta != null) {
            Notification.BubbleMetadata.Builder m = meta.getShortcutId() != null ? new Notification.BubbleMetadata.Builder(meta.getShortcutId())
                    : new Notification.BubbleMetadata.Builder(meta.getIntent(), meta.getIcon());
            if (meta.getDesiredHeight() > 0) m.setDesiredHeight(meta.getDesiredHeight());
            else if (meta.getDesiredHeightResId() != 0) m.setDesiredHeightResId(meta.getDesiredHeightResId());
            if (meta.getDeleteIntent() != null) m.setDeleteIntent(meta.getDeleteIntent());
            b.setBubbleMetadata(m.setAutoExpandBubble(false).setSuppressNotification(true).build());
        }
        return b.build();
    }

    /** Kept on disk too, so a bubble taken down just before the app was closed still comes back. */
    private static void remember(String chat, boolean hidden) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(prefs.getStringSet(HIDDEN, new HashSet<>()));
        if (hidden ? !set.add(chat) : !set.remove(chat)) return;
        prefs.edit().putStringSet(HIDDEN, set).putInt(BOOT, bootCount(app)).apply();
    }

    private static int bootCount(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0); } catch (Exception e) { return 0; }
    }
}
