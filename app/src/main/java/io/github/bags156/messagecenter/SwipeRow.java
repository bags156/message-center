package io.github.bags156.messagecenter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * A row that can be dragged sideways to trigger an action (swipe a message to reply, a chat to
 * archive). It only takes over a touch once the drag is clearly horizontal, so scrolling, taps,
 * long-presses, and links inside keep working. Hints behind the row show what the swipe does.
 */
final class SwipeRow extends FrameLayout {
    private final View content;
    private final View rightHint, leftHint;
    private final int slop, threshold, maxTravel;
    private Runnable onRight, onLeft;
    private float downX, downY;
    private boolean dragging, armed;

    SwipeRow(Context context, View content, View rightHint, View leftHint, int threshold, int maxTravel) {
        super(context);
        this.content = content; this.rightHint = rightHint; this.leftHint = leftHint;
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.threshold = threshold; this.maxTravel = maxTravel;
        if (rightHint != null) { rightHint.setAlpha(0f); addView(rightHint); }
        if (leftHint != null) { leftHint.setAlpha(0f); addView(leftHint); }
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** Dragging to the right (finger moves right) runs this. */
    SwipeRow onSwipeRight(Runnable action) { onRight = action; return this; }
    /** Dragging to the left runs this. */
    SwipeRow onSwipeLeft(Runnable action) { onLeft = action; return this; }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downX = e.getX(); downY = e.getY(); dragging = false; armed = false; return false;
            case MotionEvent.ACTION_MOVE: return startIfHorizontal(e);
            default: return false;
        }
    }

    /** Claims the touch once it is clearly a sideways drag in a direction that has an action. */
    private boolean startIfHorizontal(MotionEvent e) {
        if (dragging) return true;
        float dx = e.getX() - downX, dy = e.getY() - downY;
        boolean allowed = (dx > 0 && onRight != null) || (dx < 0 && onLeft != null);
        if (!allowed || Math.abs(dx) <= slop * 1.5f || Math.abs(dx) <= Math.abs(dy) * 2f) return false;
        dragging = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        // The press on whatever was touched is cancelled, so it doesn't count as a tap or long-press.
        content.cancelLongPress();
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        // Touches that started on empty space (nothing tappable) arrive here directly.
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = e.getX(); downY = e.getY(); dragging = false; armed = false; return onRight != null || onLeft != null; }
        if (!dragging && !(e.getActionMasked() == MotionEvent.ACTION_MOVE && startIfHorizontal(e))) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX;
                if ((dx > 0 && onRight == null) || (dx < 0 && onLeft == null)) dx = 0;
                // Resists after the threshold so the row never flies off.
                float travel = Math.abs(dx) <= threshold ? dx : Math.signum(dx) * (threshold + (Math.abs(dx) - threshold) * 0.25f);
                travel = Math.max(-maxTravel, Math.min(maxTravel, travel));
                content.setTranslationX(travel);
                float progress = Math.min(1f, Math.abs(travel) / threshold);
                if (rightHint != null) rightHint.setAlpha(travel > 0 ? progress : 0f);
                if (leftHint != null) leftHint.setAlpha(travel < 0 ? progress : 0f);
                boolean nowArmed = Math.abs(travel) >= threshold;
                if (nowArmed && !armed) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                armed = nowArmed;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean fire = armed && e.getActionMasked() == MotionEvent.ACTION_UP;
                float direction = content.getTranslationX();
                dragging = false; armed = false;
                content.animate().translationX(0).setDuration(180).start();
                if (rightHint != null) rightHint.animate().alpha(0f).setDuration(180).start();
                if (leftHint != null) leftHint.animate().alpha(0f).setDuration(180).start();
                if (fire) { Runnable action = direction > 0 ? onRight : onLeft; if (action != null) postDelayed(action, 120); }
                return true;
            default: return true;
        }
    }
}
