package io.github.bags156.messagecenter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * A slim, rounded video progress bar. Interactive: drag anywhere on it to seek; the bar
 * thickens and the knob grows while you drag. Non-interactive: a thin line along a video's edge.
 */
final class Scrubber extends View {
    interface Listener { void seek(int value, boolean done); }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), played = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean interactive;
    private final float dp;
    private int max = 1, value;
    private boolean dragging;
    private Listener listener;

    Scrubber(Context context, boolean interactive) {
        super(context);
        this.interactive = interactive;
        dp = context.getResources().getDisplayMetrics().density;
        track.setColor(0x59FFFFFF); played.setColor(0xFFFFFFFF); knob.setColor(0xFFFFFFFF);
        setMinimumHeight(Math.round((interactive ? 36 : 3) * dp));
        if (interactive) { setFocusable(true); setContentDescription("Video position"); }
    }

    void setListener(Listener l) { listener = l; }
    void setMax(int m) { max = Math.max(1, m); invalidate(); }
    void setValue(int v) { if (!dragging) { value = Math.max(0, Math.min(max, v)); invalidate(); } }
    int value() { return value; }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w), resolveSize(getSuggestedMinimumHeight(), h)); }

    @Override protected void onDraw(Canvas c) {
        float pad = interactive ? 9 * dp : 0, left = pad, right = getWidth() - pad;
        float thick = interactive ? (dragging ? 6 : 4) * dp : 3 * dp, cy = interactive ? getHeight() / 2f : getHeight() - thick / 2f;
        float x = left + (right - left) * (value / (float) max);
        c.drawRoundRect(left, cy - thick / 2, right, cy + thick / 2, thick / 2, thick / 2, track);
        if (x > left) c.drawRoundRect(left, cy - thick / 2, Math.max(x, left + thick), cy + thick / 2, thick / 2, thick / 2, played);
        if (interactive) c.drawCircle(x, cy, (dragging ? 9 : 6.5f) * dp, knob);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!interactive || !isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                value = toValue(e.getX()); invalidate(); if (listener != null) listener.seek(value, false); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                value = toValue(e.getX()); dragging = false; invalidate(); if (listener != null) listener.seek(value, true); return true;
            default: return true;
        }
    }

    private int toValue(float x) {
        float pad = 9 * dp, w = Math.max(1, getWidth() - 2 * pad);
        return Math.round(Math.max(0, Math.min(1, (x - pad) / w)) * max);
    }
}
