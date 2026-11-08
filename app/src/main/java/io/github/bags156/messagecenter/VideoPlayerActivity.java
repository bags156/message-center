package io.github.bags156.messagecenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.File;
import java.util.Locale;

/**
 * Plays a downloaded video inside Message Center: play/pause, seek, Save and Share. Turning
 * the phone sideways keeps playing (the screen just resizes), and leaving pauses it.
 */
public final class VideoPlayerActivity extends Activity {
    private static final String PATH = "path", MIME = "mime", NAME = "name";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private VideoView video; private SeekBar seek; private TextView elapsed, total; private ImageView play; private View top, bottom; private ProgressBar spinner;
    private File file; private String mime, name;
    private int resumeAt; private boolean wasPlaying = true, prepared, dragging;

    static Intent open(Context context, File file, String mime, String name) {
        return new Intent(context, VideoPlayerActivity.class).putExtra(PATH, file.getPath()).putExtra(MIME, mime).putExtra(NAME, name);
    }

    private int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private final Runnable tick = new Runnable() { @Override public void run() {
        if (prepared && !dragging) { int at = video.getCurrentPosition(); seek.setProgress(at); elapsed.setText(clock(at)); }
        ui.postDelayed(this, 250);
    } };
    private final Runnable hideControls = () -> { if (video.isPlaying()) showControls(false); };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Diagnostics.init(this);
        String path = getIntent().getStringExtra(PATH); mime = getIntent().getStringExtra(MIME); name = getIntent().getStringExtra(NAME);
        if (path == null || !(file = new File(path)).isFile()) { finish(); return; }
        if (state != null) { resumeAt = state.getInt("at", 0); wasPlaying = state.getBoolean("playing", true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout box = new FrameLayout(this); box.setBackgroundColor(Color.BLACK);
        video = new VideoView(this); box.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        spinner = new ProgressBar(this); box.addView(spinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));

        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(8), dp(36), dp(8), dp(8)); bar.setBackgroundColor(0x66000000);
        ImageView close = new ImageView(this); close.setImageResource(R.drawable.ic_close); close.setPadding(dp(12), dp(12), dp(12), dp(12)); close.setContentDescription("Close"); close.setOnClickListener(v -> finish());
        bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(action("Save", v -> save())); bar.addView(action("Share", v -> share()));
        top = bar; box.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL); controls.setPadding(dp(8), dp(8), dp(16), dp(28)); controls.setBackgroundColor(0x66000000);
        play = new ImageView(this); play.setImageResource(R.drawable.ic_pause); play.setPadding(dp(12), dp(12), dp(12), dp(12)); play.setContentDescription("Pause");
        play.setOnClickListener(v -> toggle()); controls.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));
        elapsed = label("0:00"); controls.addView(elapsed);
        seek = new SeekBar(this); controls.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
        total = label("0:00"); controls.addView(total);
        bottom = controls; box.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        box.setOnClickListener(v -> { boolean show = top.getVisibility() != View.VISIBLE; showControls(show); if (show) scheduleHide(); });
        setContentView(box);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) { if (fromUser) { elapsed.setText(clock(value)); video.seekTo(value); } }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; ui.removeCallbacks(hideControls); }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; scheduleHide(); }
        });
        video.setOnPreparedListener(mp -> {
            prepared = true; spinner.setVisibility(View.GONE);
            int length = video.getDuration(); seek.setMax(Math.max(0, length)); total.setText(clock(length));
            if (resumeAt > 0) video.seekTo(resumeAt);
            if (wasPlaying) { video.start(); scheduleHide(); }
            updatePlay(); ui.removeCallbacks(tick); ui.post(tick);
        });
        video.setOnCompletionListener(mp -> { updatePlay(); showControls(true); seek.setProgress(seek.getMax()); });
        video.setOnErrorListener((mp, what, extra) -> { spinner.setVisibility(View.GONE); Diagnostics.badFile(file, "video player error " + what + "/" + extra + ", " + mime); cantPlay(); return true; });
        video.setVideoPath(file.getPath());
    }

    private TextView action(String text, View.OnClickListener click) {
        TextView t = new TextView(this); t.setText(text); t.setTextColor(Color.WHITE); t.setTextSize(16); t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(14), dp(10), dp(14), dp(10)); t.setOnClickListener(click); return t;
    }
    private TextView label(String text) { TextView t = new TextView(this); t.setText(text); t.setTextColor(Color.WHITE); t.setTextSize(13); t.setPadding(dp(6), 0, dp(6), 0); return t; }

    private void toggle() {
        if (video.isPlaying()) { video.pause(); showControls(true); ui.removeCallbacks(hideControls); }
        else { if (prepared && video.getCurrentPosition() >= video.getDuration() - 250) video.seekTo(0); video.start(); scheduleHide(); }
        updatePlay();
    }
    private void updatePlay() {
        boolean playing = video.isPlaying();
        play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play); play.setContentDescription(playing ? "Pause" : "Play");
    }
    private void showControls(boolean show) { int v = show ? View.VISIBLE : View.GONE; top.setVisibility(v); bottom.setVisibility(v); }
    private void scheduleHide() { ui.removeCallbacks(hideControls); ui.postDelayed(hideControls, 3000); }

    static String clock(int ms) {
        int s = Math.max(0, ms) / 1000, h = s / 3600, m = (s % 3600) / 60; s %= 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }

    private Uri uri() { return MediaProvider.uri(file, name); }

    private void share() {
        Intent send = new Intent(Intent.ACTION_SEND).setType(mime == null ? "video/*" : mime).putExtra(Intent.EXTRA_STREAM, uri()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(name, uri())); startActivity(Intent.createChooser(send, "Share"));
    }

    /** Copies into Movies → "Message Center", where the gallery and Files apps can see it. */
    private void save() {
        new Thread(() -> {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name); v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Message Center"); v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri item = null; String result;
            try {
                item = getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (item == null) throw new java.io.IOException("insert");
                try (java.io.InputStream in = new java.io.FileInputStream(file); java.io.OutputStream out = getContentResolver().openOutputStream(item)) { if (out == null) throw new java.io.IOException("open"); in.transferTo(out); }
                v.clear(); v.put(MediaStore.MediaColumns.IS_PENDING, 0); getContentResolver().update(item, v, null, null);
                result = "Saved to Movies/Message Center";
            } catch (Exception e) { if (item != null) getContentResolver().delete(item, null, null); result = "Could not save it to your phone."; }
            String r = result; ui.post(() -> Toast.makeText(this, r, Toast.LENGTH_SHORT).show());
        }, "save-video").start();
    }

    private void cantPlay() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle("Can't play this video here").setMessage("Android's video player can't handle this file. Another app on your phone may be able to.")
            .setPositiveButton("Open in another app", (d, w) -> {
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri(), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(view); } catch (Exception e) { Toast.makeText(this, "No app on this phone can open it. Try Share.", Toast.LENGTH_SHORT).show(); }
                finish();
            }).setNegativeButton("Close", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
    }

    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); video.requestLayout(); }

    @Override protected void onPause() {
        super.onPause();
        if (video != null && prepared) { resumeAt = video.getCurrentPosition(); wasPlaying = video.isPlaying(); video.pause(); updatePlay(); showControls(true); }
    }

    @Override protected void onResume() {
        super.onResume();
        if (video != null && prepared) { video.seekTo(resumeAt); if (wasPlaying) { video.start(); scheduleHide(); } updatePlay(); }
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putInt("at", resumeAt); out.putBoolean("playing", wasPlaying); }

    @Override protected void onDestroy() { ui.removeCallbacksAndMessages(null); if (video != null) video.stopPlayback(); super.onDestroy(); }
}
