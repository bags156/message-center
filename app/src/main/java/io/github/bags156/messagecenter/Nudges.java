package io.github.bags156.messagecenter;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import org.json.JSONObject;
import java.util.TimeZone;

/**
 * Unread nudges: when someone in a pinned chat messages you and the chat is still unread
 * (or you marked it unread) after a while, a reminder notification appears, repeating at the
 * chosen interval up to three times. Reading, replying, or "Not until next message" stops it.
 * Muted and snoozed chats never nudge; nothing is sent between 10 PM and 8 AM.
 */
public final class Nudges extends BroadcastReceiver {
    static final String CHECK = "io.github.bags156.messagecenter.NUDGE_CHECK";
    static final String DISMISS = "io.github.bags156.messagecenter.NUDGE_DISMISS";
    static final String CHANNEL = "nudges";
    private static final int NOTIFICATION_ID = 3;

    private static SharedPreferences state(Context c) { return c.getSharedPreferences("nudges", Context.MODE_PRIVATE); }

    /** Minutes between nudges; 0 when turned off. On by default, every 2 hours. */
    static int minutes(Context context) { return context.getSharedPreferences("paired", Context.MODE_PRIVATE).getInt("nudge_minutes", 120); }
    static void setMinutes(Context context, int minutes) {
        context.getSharedPreferences("paired", Context.MODE_PRIVATE).edit().putInt("nudge_minutes", minutes).apply();
        reschedule(context);
    }

    /** Records when a message actually arrived here (its timestamp is when it was sent, which can be much earlier). */
    static void arrived(Context context, JSONObject message) {
        String chat = message.optString("conversationID"), mid = message.optString("messageID");
        if (!chat.isEmpty() && !mid.isEmpty()) state(context).edit().putString("arrival:" + chat, mid + "|" + System.currentTimeMillis()).apply();
    }
    static void cancel(Context context, String chat) { context.getSystemService(NotificationManager.class).cancel("nudge:" + chat, NOTIFICATION_ID); }

    /** Posts any nudges that are due and sets an alarm for the next one. Safe to call often. */
    static synchronized void reschedule(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        PendingIntent check = PendingIntent.getBroadcast(app, 11, new Intent(app, Nudges.class).setAction(CHECK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long delay = minutes(app) * 60_000L;
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
        TimeZone zone = TimeZone.getDefault();
        SharedPreferences s = state(app);
        ChatSettings settings = new ChatSettings(app);
        Nicknames.load(app);
        PairedStore shared = PairedService.sharedStore(), own = shared == null ? new PairedStore(app) : null, store = shared != null ? shared : own;
        SharedPreferences.Editor edit = s.edit();
        String nextTitle = "", problem = "";
        try {
            for (JSONObject chat : store.chats("", 1)) {
                String id = chat.optString("conversationID");
                if (delay <= 0 || !settings.pinned(chat) || !settings.unread(chat) || settings.muted(id) || settings.snoozed(id)) { nm.cancel("nudge:" + id, NOTIFICATION_ID); edit.remove("since:" + id); continue; }
                // Count from when the chat started qualifying (just pinned, just marked unread), not only from the message.
                long since = s.getLong("since:" + id, 0);
                if (since == 0) { since = now; edit.putLong("since:" + id, now); }
                JSONObject last = store.latestIncoming(id);
                if (last == null) continue;
                String mid = last.optString("messageID"); long received = PairedProtocol.time(last);
                String arrival = s.getString("arrival:" + id, "");
                if (arrival.startsWith(mid + "|")) try { received = Math.max(received, Long.parseLong(arrival.substring(mid.length() + 1))); } catch (NumberFormatException ignored) {}
                received = Math.max(received, since);
                if (mid.equals(s.getString("dismissed:" + id, ""))) { nm.cancel("nudge:" + id, NOTIFICATION_ID); continue; }
                boolean same = mid.equals(s.getString("for:" + id, ""));
                long lastNudge = same ? s.getLong("at:" + id, 0) : 0; int sent = same ? s.getInt("count:" + id, 0) : 0;
                long due = NudgeTiming.next(received, lastNudge, sent, delay, zone);
                if (due < 0) continue;
                if (due <= now) {
                    post(app, nm, chat, last);
                    s.edit().putString("for:" + id, mid).putLong("at:" + id, now).putInt("count:" + id, sent + 1).apply();
                    due = NudgeTiming.next(received, now, sent + 1, delay, zone);
                    if (due < 0) continue;
                }
                if (due < next) { next = due; nextTitle = PairedProtocol.title(chat); }
            }
        } catch (Exception e) {
            problem = String.valueOf(e);
        } finally {
            // What's coming up, so Settings can show that reminders are working.
            edit.putLong("next_at", next == Long.MAX_VALUE ? 0 : next).putString("next_title", nextTitle).putLong("checked_at", now).putString("problem", problem);
            edit.apply(); if (own != null) own.close();
        }
        if (next == Long.MAX_VALUE) alarms.cancel(check);
        // Exact: an ordinary alarm may arrive up to 75% of the wait late (1.5 hours late on a 2-hour reminder).
        else if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, check);
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, check);
    }

    /** One line for Settings: the next reminder, the last one, or why none is waiting. */
    static String summary(Context context) {
        if (minutes(context) <= 0) return "";
        SharedPreferences s = state(context);
        java.text.DateFormat time = android.text.format.DateFormat.getTimeFormat(context);
        long next = s.getLong("next_at", 0), last = s.getLong("last_at", 0), today = System.currentTimeMillis();
        String when = next <= 0 ? "" : (android.text.format.DateUtils.isToday(next) ? "" : "tomorrow ") + time.format(new java.util.Date(next));
        StringBuilder b = new StringBuilder(next > 0 ? "Next reminder: " + s.getString("next_title", "a pinned chat") + " at " + when : "No reminder waiting. Only pinned chats with unread messages get them.");
        if (last > 0 && today - last < 7 * 86_400_000L) b.append("\nLast sent: ").append(s.getString("last_title", "")).append(", ").append(android.text.format.DateUtils.getRelativeTimeSpanString(last, today, 60_000L));
        if (!s.getString("problem", "").isEmpty()) b.append("\nLast check failed: ").append(s.getString("problem", ""));
        return b.toString();
    }

    /** A sample reminder, to check that they show up and how they look. */
    static void test(Context context) {
        try {
            post(context, context.getSystemService(NotificationManager.class), new JSONObject(), new JSONObject(), false);
        } catch (Exception ignored) {}
    }

    private static void post(Context context, NotificationManager nm, JSONObject chat, JSONObject message) { post(context, nm, chat, message, true); }
    private static void post(Context context, NotificationManager nm, JSONObject chat, JSONObject message, boolean real) {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Unread reminders", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Reminders about unread messages in pinned chats"); nm.createNotificationChannel(ch);
        }
        String id = chat.optString("conversationID"), mid = message.optString("messageID"), title = PairedProtocol.title(chat);
        String body = real ? PairedProtocol.body(message) : "This is what an unread reminder looks like. Real ones offer Mark read and Not until next message.";
        if (!real) {
            PendingIntent app = PendingIntent.getActivity(context, 0x7e57, new Intent(context, PairedActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nm.notify("nudge:test", NOTIFICATION_ID, new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Still unread: Test reminder")
                .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setCategory(Notification.CATEGORY_REMINDER).setContentIntent(app).setAutoCancel(true).build());
            return;
        }
        Intent open = new Intent(context, PairedActivity.class).setAction(PairedActivity.OPEN_CHAT).setData(Uri.parse("messagecenter://nudge/" + id))
            .putExtra("chat", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int code = id.hashCode();
        PendingIntent tap = PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent read = PendingIntent.getBroadcast(context, code ^ 0x5151, new Intent(context, NotificationActions.class).setAction(NotificationActions.READ)
            .setData(Uri.parse("messagecenter://nudge-read/" + id)).putExtra("chat", id), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent later = PendingIntent.getBroadcast(context, code ^ 0x2727, new Intent(context, Nudges.class).setAction(DISMISS)
            .setData(Uri.parse("messagecenter://nudge-dismiss/" + id)).putExtra("chat", id).putExtra("message", mid), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Still unread: " + title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(tap).setAutoCancel(true)
            .addAction(new Notification.Action.Builder(null, "Mark read", read).build())
            .addAction(new Notification.Action.Builder(null, "Not until next message", later).build()).build();
        nm.notify("nudge:" + id, NOTIFICATION_ID, n);
        state(context).edit().putLong("last_at", System.currentTimeMillis()).putString("last_title", title).apply();
    }

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (DISMISS.equals(action)) {
            String chat = intent.getStringExtra("chat"), message = intent.getStringExtra("message");
            if (chat == null || message == null) return;
            state(context).edit().putString("dismissed:" + chat, message).apply();
            cancel(context, chat);
        } else if (CHECK.equals(action)) {
            PendingResult pending = goAsync();
            new Thread(() -> { try { reschedule(context); } finally { pending.finish(); } }, "nudges").start();
        }
    }
}
