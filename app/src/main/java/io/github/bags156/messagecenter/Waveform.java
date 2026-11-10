package io.github.bags156.messagecenter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * A voice message's waveform: rounded bars, the played part in a stronger color. Drag or tap it
 * to move through the message. In live mode (while recording) the newest levels come in on the
 * right and scroll left.
 */
final class Waveform extends View {
    interface Seek { void to(float fraction, boolean done); }

    private static final int LIVE_MAX = 400;
    private final float d = getResources().getDisplayMetrics().density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private float[] peaks;
    private float progress;
    private int played = 0xff000000, rest = 0x55000000;
    private Seek seek;
    private boolean dragging;
    private float dragAt;
    private final float[] live = new float[LIVE_MAX];
    private int liveCount;
    private boolean liveMode;

    Waveform(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

    /** Levels from 0 to 1 across the message; null while they aren't known (a flat line is shown). */
    void setPeaks(float[] peaks) { this.peaks = peaks; invalidate(); }
    void setProgress(float p) { if (!dragging && Math.abs(p - progress) > 0.0005f) { progress = Math.max(0, Math.min(1, p)); invalidate(); } }
    void setColors(int played, int rest) { this.played = played; this.rest = rest; invalidate(); }
    void setOnSeek(Seek seek) { this.seek = seek; }
    boolean dragging() { return dragging; }
    void liveMode() { liveMode = true; liveCount = 0; invalidate(); }
    /** While recording: the newest level, 0 to 1. */
    void push(float level) {
        if (liveCount == LIVE_MAX) { System.arraycopy(live, 1, live, 0, LIVE_MAX - 1); liveCount--; }
        live[liveCount++] = Math.max(0, Math.min(1, level));
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float barW = 3 * d, gap = 2 * d, min = 3 * d;
        int count = Math.max(1, (int) ((w + gap) / (barW + gap)));
        float shown = dragging ? dragAt : progress;
        for (int i = 0; i < count; i++) {
            float level;
            if (liveMode) {
                int from = liveCount - count + i;
                level = from >= 0 ? live[from] : 0;
            } else if (peaks == null || peaks.length == 0) {
                level = 0;
            } else {
                // Each bar is the loudest stretch it covers.
                int a = i * peaks.length / count, b = Math.max(a + 1, (i + 1) * peaks.length / count);
                level = 0;
                for (int k = a; k < b && k < peaks.length; k++) level = Math.max(level, peaks[k]);
            }
            float bh = Math.max(min, level * h), x = i * (barW + gap), top = (h - bh) / 2;
            boolean done = liveMode || (x + barW / 2) / w <= shown;
            paint.setColor(done ? played : rest);
            bar.set(x, top, x + barW, top + bh);
            c.drawRoundRect(bar, barW / 2, barW / 2, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (seek == null || liveMode || getWidth() == 0) return false;
        float f = Math.max(0, Math.min(1, e.getX() / getWidth()));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Dragging along the waveform shouldn't scroll the chat or swipe the message.
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true; dragAt = f; seek.to(f, false); invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                dragAt = f; seek.to(f, false); invalidate(); return true;
            case MotionEvent.ACTION_UP:
                dragging = false; progress = f; seek.to(f, true); invalidate(); performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false; invalidate(); return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }
}
