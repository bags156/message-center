package io.github.bags156.messagecenter;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Drag the conversation to the left to see when each message was sent or received (like
 * iMessage); it slides back when you let go. Only a clearly leftward drag is taken, so
 * scrolling, taps, long-presses, and swipe-right-to-reply keep working.
 */
final class TimeRevealLayout extends FrameLayout {
    interface Listener { void reveal(float amount); }

    private final int slop, max;
    private final Listener listener;
    private float downX, downY, startX, base, current;
    private boolean dragging, enabled = true;
    private ValueAnimator back;

    TimeRevealLayout(Context context, int max, Listener listener) {
        super(context);
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.max = max; this.listener = listener;
    }

    void setRevealEnabled(boolean on) { enabled = on; if (!on && current != 0) settle(); }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downX = e.getX(); downY = e.getY(); dragging = false; return false;
            case MotionEvent.ACTION_MOVE:
                if (!enabled || dragging) return dragging;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (dx < -slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (back != null) back.cancel();
                    dragging = true; startX = e.getX(); base = current;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            default: return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float pulled = Math.max(0, base + startX - e.getX());
                // Follows the finger up to the full width of the times, then resists.
                float amount = pulled <= max ? pulled : max + (pulled - max) * 0.2f;
                set(Math.min(amount, max * 1.3f));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false; settle(); return true;
            default: return true;
        }
    }

    private void set(float amount) { current = amount; listener.reveal(amount); }

    private void settle() {
        if (back != null) back.cancel();
        back = ValueAnimator.ofFloat(current, 0f);
        back.setDuration(220);
        back.addUpdateListener(a -> set((float) a.getAnimatedValue()));
        back.start();
    }
}
