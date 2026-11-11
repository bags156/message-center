package io.github.bags156.messagecenter;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** Keeps live inbox updates from replacing rows under a finger or a running fling. */
final class InboxScrollView extends ScrollView {
    private static final long SETTLE_MS = 160;
    private boolean touching, moving;
    private Runnable pendingUpdate;
    private final Runnable settled = () -> {
        if (touching) return;
        moving = false;
        Runnable update = pendingUpdate;
        pendingUpdate = null;
        if (update != null) update.run();
    };

    InboxScrollView(Context context) { super(context); }

    /** The newest request replaces earlier ones; the eventual redraw reads the current store. */
    boolean deferUpdate(Runnable update) {
        if (!touching && !moving) return false;
        pendingUpdate = update;
        return true;
    }

    private void waitForScrolling() {
        removeCallbacks(settled);
        if (!touching) postDelayed(settled, SETTLE_MS);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            moving = true;
            removeCallbacks(settled);
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_DOWN && !handled)) {
            touching = false;
            waitForScrolling();
        }
        return handled;
    }

    @Override public void fling(int velocityY) {
        if (velocityY != 0) moving = true;
        super.fling(velocityY);
        if (moving) waitForScrolling();
    }

    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (moving) waitForScrolling();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(settled);
        pendingUpdate = null;
        touching = false;
        moving = false;
        super.onDetachedFromWindow();
    }
}
