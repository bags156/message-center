package io.github.bags156.messagecenter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;

/**
 * Plays a video right inside its chat tile: tap to play or pause, a sound toggle, the time left,
 * a thin progress line, and a button to go full screen. It survives the chat redrawing: the
 * same player and picture move into the new tile (the video surface is kept, not recreated).
 */
final class InlineVideo implements TextureView.SurfaceTextureListener {
    interface Host { void fullScreen(InlineVideo video); void failed(InlineVideo video, String why); }

    final String key;
    final File file;
    final String mime;
    private final Context context;
    private final Host host;
    private final FrameLayout root;
    private final TextureView texture;
    private final Scrubber line;
    private final ImageView sound;
    private final TextView left;
    private final ProgressBar spinner;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private SurfaceTexture kept;
    private Surface surface;
    private View playBadge;
    private boolean prepared, wantPlay = true, muted, released, shown;
    private int videoW, videoH;
    private AudioFocusRequest focus;

    private final Runnable tick = new Runnable() { @Override public void run() {
        if (player != null && prepared) { int at = player.getCurrentPosition(), length = player.getDuration(); line.setValue(at); left.setText(VideoPlayerActivity.clock(Math.max(0, length - at))); }
        ui.postDelayed(this, 200);
    } };

    InlineVideo(Context context, String key, File file, String mime, boolean muted, Host host) {
        this.context = context; this.key = key; this.file = file; this.mime = mime; this.muted = muted; this.host = host;
        float dp = context.getResources().getDisplayMetrics().density;
        root = new FrameLayout(context);
        texture = new TextureView(context); texture.setSurfaceTextureListener(this); texture.setAlpha(0f);
        root.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(context); root.addView(spinner, new FrameLayout.LayoutParams(px(dp, 40), px(dp, 40), Gravity.CENTER));
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x8C000000, 0x00000000});
        View shade = new View(context); shade.setBackground(scrim); root.addView(shade, new FrameLayout.LayoutParams(-1, px(dp, 56), Gravity.BOTTOM));
        line = new Scrubber(context, false); root.addView(line, new FrameLayout.LayoutParams(-1, px(dp, 3), Gravity.BOTTOM));
        sound = button(context, dp, muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on, muted ? "Turn sound on" : "Turn sound off");
        sound.setOnClickListener(v -> setMuted(!this.muted));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(px(dp, 36), px(dp, 36), Gravity.BOTTOM | Gravity.START); sl.setMargins(px(dp, 8), 0, 0, px(dp, 10)); root.addView(sound, sl);
        left = new TextView(context); left.setTextColor(Color.WHITE); left.setTextSize(12); left.setShadowLayer(3, 0, 1, 0x99000000);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); ll.setMargins(0, 0, px(dp, 12), px(dp, 16)); root.addView(left, ll);
        ImageView expand = button(context, dp, R.drawable.ic_fullscreen, "Full screen");
        expand.setOnClickListener(v -> host.fullScreen(this));
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(px(dp, 36), px(dp, 36), Gravity.TOP | Gravity.END); el.setMargins(0, px(dp, 8), px(dp, 8), 0); root.addView(expand, el);
    }

    private static int px(float dp, int v) { return Math.round(v * dp); }
    private static ImageView button(Context c, float dp, int icon, String label) {
        ImageView b = new ImageView(c); b.setImageResource(icon); int pad = px(dp, 8); b.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(0x73000000); b.setBackground(bg); b.setContentDescription(label); return b;
    }

    /** Puts the player into this tile (moving it from the old one after the chat redraws). playBadge is the tile's ▶, hidden while playing. */
    void attach(FrameLayout box, View playBadge) {
        this.playBadge = playBadge;
        ViewGroup parent = (ViewGroup) root.getParent();
        if (parent != box) { if (parent != null) parent.removeView(root); box.addView(root, Math.min(1, box.getChildCount()), new FrameLayout.LayoutParams(-1, -1)); }
        if (playBadge != null) playBadge.bringToFront();
        updateBadge();
    }

    boolean playing() { return player != null && prepared && player.isPlaying(); }
    int position() { return player != null && prepared ? player.getCurrentPosition() : 0; }
    boolean muted() { return muted; }

    void toggle() { if (playing()) pause(); else play(); }

    void play() {
        wantPlay = true;
        if (player == null || !prepared) return;
        if (!muted) requestFocus();
        if (player.getCurrentPosition() >= player.getDuration() - 200) player.seekTo(0);
        player.start(); ui.removeCallbacks(tick); ui.post(tick); updateBadge();
    }

    void pause() {
        wantPlay = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
        ui.removeCallbacks(tick); updateBadge(); abandonFocus();
    }

    void setMuted(boolean on) {
        muted = on;
        if (player != null) player.setVolume(on ? 0 : 1, on ? 0 : 1);
        sound.setImageResource(on ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        sound.setContentDescription(on ? "Turn sound on" : "Turn sound off");
        if (on) abandonFocus(); else if (playing()) requestFocus();
    }

    /** Stops playback and frees the player and its picture. */
    void release() {
        released = true; ui.removeCallbacks(tick); abandonFocus();
        if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        if (surface != null) { surface.release(); surface = null; }
        if (kept != null) { kept.release(); kept = null; }
        ViewGroup parent = (ViewGroup) root.getParent(); if (parent != null) parent.removeView(root);
        if (playBadge != null) playBadge.setVisibility(View.VISIBLE);
    }

    private void updateBadge() { if (playBadge != null) playBadge.setVisibility(playing() || !prepared && wantPlay ? View.GONE : View.VISIBLE); }

    private void open() {
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            player.setDataSource(file.getPath());
            player.setSurface(surface);
            player.setVolume(muted ? 0 : 1, muted ? 0 : 1);
            player.setOnVideoSizeChangedListener((mp, w, h) -> { videoW = w; videoH = h; fit(); });
            player.setOnPreparedListener(mp -> {
                if (released) return;
                prepared = true; spinner.setVisibility(View.GONE); line.setMax(mp.getDuration()); left.setText(VideoPlayerActivity.clock(mp.getDuration()));
                if (wantPlay) play(); else updateBadge();
            });
            player.setOnInfoListener((mp, what, extra) -> { if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !shown) { shown = true; texture.animate().alpha(1f).setDuration(150).start(); } return false; });
            player.setOnCompletionListener(mp -> { wantPlay = false; ui.removeCallbacks(tick); line.setValue(line.value() + 1_000_000); left.setText(VideoPlayerActivity.clock(mp.getDuration())); updateBadge(); abandonFocus(); });
            player.setOnErrorListener((mp, what, extra) -> { spinner.setVisibility(View.GONE); host.failed(this, "video error " + what + "/" + extra); return true; });
            player.prepareAsync();
        } catch (Exception e) {
            spinner.setVisibility(View.GONE); host.failed(this, String.valueOf(e));
        }
    }

    /** Fills the tile like the photo preview does (cropping the edges), instead of stretching. */
    private void fit() {
        int vw = texture.getWidth(), vh = texture.getHeight();
        if (vw == 0 || vh == 0 || videoW == 0 || videoH == 0) return;
        float scale = Math.max(vw / (float) videoW, vh / (float) videoH);
        Matrix m = new Matrix(); m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f); texture.setTransform(m);
    }

    private void requestFocus() {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (focus == null) focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener(change -> { if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) ui.post(this::pause); }).build();
        audio.requestAudioFocus(focus);
    }
    private void abandonFocus() { if (focus != null) context.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus); }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (released) return;
        if (kept == null) { kept = st; surface = new Surface(st); open(); }
        else if (texture.getSurfaceTexture() != kept) texture.setSurfaceTexture(kept); // back in a redrawn tile: keep showing the same video
        fit();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { fit(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return st != kept; } // keep ours for the next tile
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}
