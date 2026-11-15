package io.github.bags156.messagecenter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.IBinder;

/**
 * Keeps a voice message recording when the phone locks or you leave the app. Android only lets an
 * app use the microphone off screen while it runs a microphone service, so this runs (with a
 * "Recording a voice message" notification and its time) only while you're recording. From the
 * notification: Stop keeps it in your message to listen to or send later; Send sends it.
 */
public final class VoiceRecordService extends Service {
    /** The recorder's side: what the notification's buttons do. */
    interface Controls { void finish(boolean send); }

    static final String CHANNEL = "voice_recording";
    private static final int ID = 7303;
    private static final String STOP = "stop", SEND = "send";

    private static Controls owner;
    private static boolean on, holding;
    private static String to = "";
    private static long since;

    /** Whether the recording can carry on off screen (the microphone service is running). */
    static boolean holding() { return holding; }

    /** Recording started on owner's screen (Android only allows the microphone service from there). toChat is blank to keep it private. */
    static void start(Context c, Controls o, String toChat, long startedAt) {
        Context app = c.getApplicationContext();
        owner = o; to = toChat == null ? "" : toChat; since = startedAt;
        if (on) return;
        try { app.startForegroundService(new Intent(app, VoiceRecordService.class)); on = true; }
        catch (Exception e) { Diagnostics.note("Recording couldn't carry on off screen: " + e.getClass().getSimpleName()); }
    }

    /** The recording finished (sent, kept or thrown away): the notification goes. */
    static void stop(Context c, Controls o) {
        if (o != owner) return;
        owner = null; holding = false;
        Context app = c.getApplicationContext();
        if (on) { on = false; app.stopService(new Intent(app, VoiceRecordService.class)); }
        app.getSystemService(NotificationManager.class).cancel(ID);
    }

    private static PendingIntent action(Context c, String what) {
        return PendingIntent.getService(c, what.hashCode(), new Intent(c, VoiceRecordService.class).setAction(what), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification build(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Recording voice messages", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("While you record a voice message, so it keeps recording when your phone locks or you leave the app.");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        // Back to the recording, as it was (no new screen, which would end it).
        Intent open = new Intent(c, PairedActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Recording a voice message")
            .setContentText(to.isEmpty() ? "Stop keeps it in your message; Send sends it" : "To " + to)
            .setUsesChronometer(true).setWhen(since).setShowWhen(true)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(c, 7303, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stop), "Stop", action(c, STOP)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_send), "Send", action(c, SEND)).build())
            .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try { startForeground(ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); holding = owner != null; }
        catch (Exception e) {
            // Not allowed right now (the app wasn't on screen after all): the recording stops when you leave, as before.
            Diagnostics.note("Recording couldn't carry on off screen: " + e.getClass().getSimpleName());
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                try { startForeground(ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE); } catch (Exception ignored) {}
            }
            on = false; holding = false; stopSelf(); return START_NOT_STICKY;
        }
        Controls o = owner;
        if (o == null) { on = false; holding = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; }
        String what = intent == null ? null : intent.getAction();
        if (STOP.equals(what)) o.finish(false);
        else if (SEND.equals(what)) o.finish(true);
        return START_NOT_STICKY;
    }

    @Override public void onTimeout(int startId) { on = false; holding = false; stopSelf(); }

    @Override public void onDestroy() { on = false; holding = false; super.onDestroy(); }

    @Override public IBinder onBind(Intent intent) { return null; }
}
