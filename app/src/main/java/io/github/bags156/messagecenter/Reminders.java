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
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "Remind me about this message", and "Remind me in 1 hour" for a whole chat (hold it in the
 * list): at the chosen time a banner brings it back and opens the chat (at the message). The
 * banner can remind again in an hour. Kept on this phone; alarms are set again after a restart.
 */
public final class Reminders extends BroadcastReceiver {
    static final String FIRE = "io.github.bags156.messagecenter.REMIND", AGAIN = "io.github.bags156.messagecenter.REMIND_AGAIN";
    static final String CHANNEL = "reminders";

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("reminders", Context.MODE_PRIVATE); }

    static synchronized List<JSONObject> all(Context context) {
        List<JSONObject> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs(context).getString("items", "[]")); for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i)); }
        catch (Exception ignored) {}
        return out;
    }

    /** The reminder set on this message, or null. */
    static JSONObject forMessage(Context context, String messageID) {
        for (JSONObject o : all(context)) if (messageID.equals(o.optString("message"))) return o;
        return null;
    }

    /** The reminder set on this chat as a whole (not on one of its messages), or null. */
    static JSONObject forChat(Context context, String chat) {
        for (JSONObject o : all(context)) if (o.optString("message").isEmpty() && chat.equals(o.optString("chat"))) return o;
        return null;
    }

    /** messageID "": a reminder about the chat itself. */
    static synchronized void add(Context context, String chat, String messageID, String title, String text, long at) {
        JSONObject existing = messageID.isEmpty() ? forChat(context, chat) : forMessage(context, messageID);
        if (existing != null) remove(context, existing.optString("id"));
        JSONObject o = new JSONObject();
        try { o.put("id", UUID.randomUUID().toString()).put("chat", chat).put("message", messageID).put("title", title).put("text", text).put("at", at); } catch (Exception ignored) {}
        List<JSONObject> list = all(context); list.add(o); save(context, list);
        arm(context, o);
    }

    static synchronized void remove(Context context, String id) {
        context.getSystemService(AlarmManager.class).cancel(pending(context, id));
        List<JSONObject> list = all(context); list.removeIf(o -> id.equals(o.optString("id"))); save(context, list);
    }

    /** Sets every alarm again (alarms are cleared on restart); anything already due shows now. */
    static void rearmAll(Context context) {
        long now = System.currentTimeMillis();
        for (JSONObject o : all(context)) { if (o.optLong("at") <= now) fire(context, o.optString("id")); else arm(context, o); }
    }

    private static void arm(Context context, JSONObject o) {
        AlarmManager am = context.getSystemService(AlarmManager.class); PendingIntent p = pending(context, o.optString("id")); long at = o.optLong("at");
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
    }

    private static PendingIntent pending(Context context, String id) {
        Intent i = new Intent(context, Reminders.class).setAction(FIRE).setData(Uri.parse("messagecenter://reminder/" + id)).putExtra("id", id);
        return PendingIntent.getBroadcast(context, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void save(Context context, List<JSONObject> list) {
        JSONArray a = new JSONArray(); for (JSONObject o : list) a.put(o);
        prefs(context).edit().putString("items", a.toString()).apply();
    }

    private static void fire(Context context, String id) {
        JSONObject due = null;
        for (JSONObject o : all(context)) if (id.equals(o.optString("id"))) due = o;
        if (due == null) return;
        remove(context, id);
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        // High importance: a banner over whatever's on screen (unless Do Not Disturb, or it's been changed in Android's settings).
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Reminders you set on messages and chats"); nm.createNotificationChannel(ch);
        String chat = due.optString("chat"), message = due.optString("message");
        Intent open = new Intent(context, PairedActivity.class).setAction(PairedActivity.OPEN_CHAT)
            .setData(Uri.parse("messagecenter://reminder-open/" + id)).putExtra("chat", chat).putExtra("message", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 8, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Settings → What notifications show applies here too.
        boolean wholeChat = message.isEmpty();
        String heading = NotificationPrivacy.showName(context, chat) ? "Reminder: " + due.optString("title") : wholeChat ? "Chat reminder" : "Message reminder";
        String text = NotificationPrivacy.showText(context, chat) ? due.optString("text") : wholeChat ? "Tap to open the chat" : "Tap to see the message";
        Intent again = new Intent(context, Reminders.class).setAction(AGAIN).setData(Uri.parse("messagecenter://reminder-again/" + id))
            .putExtra("id", id).putExtra("chat", chat).putExtra("message", message).putExtra("title", due.optString("title")).putExtra("text", due.optString("text"));
        PendingIntent later = PendingIntent.getBroadcast(context, 9, again, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(tap).setAutoCancel(true)
            .addAction(new Notification.Action.Builder(null, "Again in 1 hour", later).build()).build();
        nm.notify("reminder:" + id, 2, n);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (FIRE.equals(intent.getAction())) { String id = intent.getStringExtra("id"); if (id != null) fire(context.getApplicationContext(), id); }
        else if (AGAIN.equals(intent.getAction())) {
            Context app = context.getApplicationContext(); String id = intent.getStringExtra("id"), chat = intent.getStringExtra("chat");
            if (id == null || chat == null) return;
            app.getSystemService(NotificationManager.class).cancel("reminder:" + id, 2);
            String message = intent.getStringExtra("message");
            add(app, chat, message == null ? "" : message, String.valueOf(intent.getStringExtra("title")), String.valueOf(intent.getStringExtra("text")), System.currentTimeMillis() + 3_600_000L);
        }
    }
}
