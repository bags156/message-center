package io.github.bags156.messagecenter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps a voice message playing when the phone locks or you leave the app, with its controls in
 * the notification and on the lock screen: play or pause, back or ahead 10 seconds, and a bar to
 * move through it. The chat screen that started it keeps the player; this shows what's playing and
 * passes the controls back to it. Left paused for 10 minutes, it goes.
 */
public final class VoicePlayService extends Service {
    /** The player's side: what the notification's and lock screen's buttons do. */
    interface Controls { void play(); void pause(); void seek(long ms); void skip(long byMs); void stop(); }

    static final String CHANNEL = "voice_playing";
    private static final int ID = 7302;
    private static final String PLAY = "play", PAUSE = "pause", BACK = "back", AHEAD = "ahead", STOP = "stop";
    static final long IDLE_MS = 10 * 60_000L;

    private static Controls owner;
    private static boolean on;
    private static VoicePlayService running;
    private static MediaSession session;
    private static String chat = "", who = "";
    private static Bitmap face;
    private static boolean playing;
    private static long at, length;
    private static float speed = 1f;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable idle = () -> { Controls o = owner; if (o != null) o.stop(); };

    /** Whether a voice message is showing in the notification (for the device checks). */
    static boolean showing() { return on; }
    /** The lock screen's play/pause and seeking (for the device checks). */
    static MediaSession session() { return session; }

    /**
     * A voice message playing or paused on owner's screen: shows it, starting this the first time
     * (only from the screen, where Android allows it). from is who sent it (blank to keep it private).
     */
    static void show(Context c, Controls o, String chatId, String from, Bitmap picture, boolean isPlaying, long position, long duration, float rate) {
        Context app = c.getApplicationContext();
        owner = o; chat = chatId == null ? "" : chatId; who = from == null ? "" : from; face = picture;
        playing = isPlaying; at = Math.max(0, position); length = Math.max(0, duration); speed = rate <= 0 ? 1f : rate;
        if (session == null) {
            session = new MediaSession(app, "MessageCenterVoice");
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { Controls x = owner; if (x != null) x.play(); }
                @Override public void onPause() { Controls x = owner; if (x != null) x.pause(); }
                @Override public void onStop() { Controls x = owner; if (x != null) x.stop(); }
                @Override public void onSeekTo(long pos) { Controls x = owner; if (x != null) x.seek(pos); }
                @Override public void onRewind() { Controls x = owner; if (x != null) x.skip(-10_000); }
                @Override public void onFastForward() { Controls x = owner; if (x != null) x.skip(10_000); }
                @Override public void onCustomAction(String action, android.os.Bundle extras) {
                    Controls x = owner; if (x == null) return;
                    if (BACK.equals(action)) x.skip(-10_000); else if (AHEAD.equals(action)) x.skip(10_000);
                }
            }, main);
        }
        MediaMetadata.Builder meta = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, who.isEmpty() ? "Voice message" : "Voice message from " + who)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Message Center")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, length);
        if (face != null) meta.putBitmap(MediaMetadata.METADATA_KEY_ART, face);
        session.setMetadata(meta.build());
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, at, playing ? speed : 0f)
            .addCustomAction(BACK, "Back 10 seconds", R.drawable.ic_replay10)
            .addCustomAction(AHEAD, "Ahead 10 seconds", R.drawable.ic_forward10)
            .build());
        session.setActive(true);
        main.removeCallbacks(idle);
        if (!playing) main.postDelayed(idle, IDLE_MS);
        if (!on) {
            try { app.startForegroundService(new Intent(app, VoicePlayService.class)); on = true; }
            catch (Exception e) { Diagnostics.note("Voice message controls couldn't show: " + e.getClass().getSimpleName()); }
        } else if (running != null) running.post();
    }

    /** It stopped (ended, closed, or another took its place): the notification and lock screen controls go. */
    static void gone(Context c, Controls o) {
        if (o != owner) return;
        owner = null; face = null;
        main.removeCallbacks(idle);
        if (session != null) { session.setActive(false); session.release(); session = null; }
        Context app = c.getApplicationContext();
        if (on) { on = false; app.stopService(new Intent(app, VoicePlayService.class)); }
        app.getSystemService(NotificationManager.class).cancel(ID);
    }

    private static PendingIntent action(Context c, String what) {
        return PendingIntent.getService(c, what.hashCode(), new Intent(c, VoicePlayService.class).setAction(what), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification build(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Voice messages playing", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("While a voice message plays, so it keeps going when your phone locks or you leave the app, with its controls.");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Intent open = new Intent(c, PairedActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!chat.isEmpty()) open.setAction(PairedActivity.OPEN_CHAT).setData(new Uri.Builder().scheme("messagecenter").authority("voice").appendPath(chat).build()).putExtra("chat", chat);
        Notification.Builder b = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(who.isEmpty() ? "Voice message" : "Voice message from " + who)
            .setContentText(playing ? "Playing" : "Paused")
            .setOngoing(playing).setOnlyAlertOnce(true).setShowWhen(false).setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(PendingIntent.getActivity(c, 7302, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setDeleteIntent(action(c, STOP))
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_replay10), "Back 10 seconds", action(c, BACK)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, playing ? R.drawable.ic_pause : R.drawable.ic_play), playing ? "Pause" : "Play", action(c, playing ? PAUSE : PLAY)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_forward10), "Ahead 10 seconds", action(c, AHEAD)).build());
        if (face != null) b.setLargeIcon(face);
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        b.setStyle(style);
        return b.build();
    }

    private void post() {
        try { getSystemService(NotificationManager.class).notify(ID, build(this)); } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        running = this;
        try { startForeground(ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); }
        catch (Exception e) { Diagnostics.note("Voice message controls couldn't stay on: " + e.getClass().getSimpleName()); on = false; stopSelf(); return START_NOT_STICKY; }
        Controls o = owner;
        if (o == null) { on = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; }
        String what = intent == null ? null : intent.getAction();
        if (PLAY.equals(what)) o.play();
        else if (PAUSE.equals(what)) o.pause();
        else if (BACK.equals(what)) o.skip(-10_000);
        else if (AHEAD.equals(what)) o.skip(10_000);
        else if (STOP.equals(what)) o.stop();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { if (running == this) running = null; on = false; super.onDestroy(); }

    @Override public IBinder onBind(Intent intent) { return null; }
}
