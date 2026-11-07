package io.github.bags156.messagecenter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** An image that fits the screen and can be pinched, double-tapped, and dragged. */
final class ZoomImageView extends ImageView {
    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private float base = 1f, scale = 1f;
    private Runnable onTap;

    ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY()); return true; }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) { if (scale > 1.01f) { matrix.postTranslate(-dx, -dy); clamp(); } return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { if (scale > 1.01f) fit(); else zoomBy(2.5f, e.getX(), e.getY()); return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (onTap != null) onTap.run(); return true; }
        });
    }

    void setOnTap(Runnable tap) { onTap = tap; }

    @Override public void setImageDrawable(Drawable drawable) { super.setImageDrawable(drawable); post(this::fit); }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); fit(); }

    private void fit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        base = Math.min(getWidth() / dw, getHeight() / dh); scale = 1f;
        matrix.reset(); matrix.postScale(base, base); matrix.postTranslate((getWidth() - dw * base) / 2f, (getHeight() - dh * base) / 2f);
        setImageMatrix(matrix);
    }

    private void zoomBy(float factor, float x, float y) {
        float next = Math.max(1f, Math.min(6f, scale * factor));
        factor = next / scale; scale = next;
        matrix.postScale(factor, factor, x, y);
        clamp();
    }

    /** Keeps the image covering the screen when zoomed, and centered when it is smaller. */
    private void clamp() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(r);
        float dx = 0, dy = 0, w = getWidth(), h = getHeight();
        if (r.width() <= w) dx = (w - r.width()) / 2f - r.left; else if (r.left > 0) dx = -r.left; else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2f - r.top; else if (r.top > 0) dy = -r.top; else if (r.bottom < h) dy = h - r.bottom;
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        scaler.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (scale > 1.01f && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }
}
