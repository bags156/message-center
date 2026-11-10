package io.github.bags156.messagecenter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Keeps a voice message transcription going after you leave the app. Android only lets the speech
 * recognizer listen for an app that's on screen or running a microphone service, so this runs
 * (with a "Transcribing…" notification) only while a transcription you started is under way.
 * Nothing is recorded: the recognizer hears the voice message, not the microphone.
 */
public final class TranscribeService extends Service {
    private static final String CHANNEL = "transcribing";
    private static final int ID = 7301;
    private static boolean on;

    /** Starts it; only from the screen (Android won't allow a microphone service from the background). */
    static void start(Context context) {
        if (on) return;
        try { context.startForegroundService(new Intent(context, TranscribeService.class)); on = true; }
        catch (Exception ignored) { /* the transcription still runs while the app is open */ }
    }

    static void stop(Context context) {
        if (!on) return;
        on = false;
        context.stopService(new Intent(context, TranscribeService.class));
    }

    /** "Transcribing a voice message… 40%". */
    static void progress(Context context, int done, int total) {
        if (!on || total <= 1) return;
        try { context.getSystemService(NotificationManager.class).notify(ID, notification(context, Math.round(100f * done / total))); }
        catch (Exception ignored) {}
    }

    private static Notification notification(Context context, int percent) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Transcribing voice messages", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown while a voice message you asked for is being transcribed, so it can finish if you leave the app.");
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, PairedActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transcribing a voice message" + (percent > 0 ? "… " + percent + "%" : "…"))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open);
        if (percent >= 0) b.setProgress(100, percent, percent == 0);
        return b.build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try { startForeground(ID, notification(this, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); }
        catch (Exception e) {
            // Not allowed right now (the app wasn't on screen after all). Android still expects this service to
            // become a foreground one, so it does briefly as a short service, then stops; the transcription
            // carries on while the app is open.
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                try { startForeground(ID, notification(this, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE); } catch (Exception ignored) {}
            }
            on = false; stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onTimeout(int startId) { on = false; stopSelf(); }

    @Override public void onDestroy() { on = false; super.onDestroy(); }

    @Override public IBinder onBind(Intent intent) { return null; }
}
