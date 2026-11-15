package io.github.bags156.messagecenter;

import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

/**
 * A chat opened from a floating chat head: the same chat screen as a bubble's, in a rounded window
 * over whatever app is on screen. Tapping outside it, or Back, closes it and the heads return.
 */
public final class FloatingChatActivity extends PairedActivity {
    /** Whether a floating chat is on screen (the heads stay aside meanwhile). */
    static volatile boolean showing;
    /** The one on screen: an older one closing late (when another replaced it) doesn't bring the heads back. */
    private static FloatingChatActivity current;

    @Override protected boolean bubble() { return true; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        WindowManager wm = getSystemService(WindowManager.class);
        Rect screen = wm.getCurrentWindowMetrics().getBounds();
        android.graphics.Insets bars = wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        float density = getResources().getDisplayMetrics().density;
        int margin = Math.round(8 * density);
        w.setLayout(screen.width() - 2 * margin, Math.round((screen.height() - bars.top - bars.bottom) * 0.74f));
        w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        WindowManager.LayoutParams a = w.getAttributes();
        a.y = bars.top + margin;
        a.dimAmount = 0.3f;
        w.setAttributes(a);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        setFinishOnTouchOutside(true);
        float radius = 22 * density;
        View decor = w.getDecorView();
        decor.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); } });
        decor.setClipToOutline(true);
    }

    @Override protected void onResume() { super.onResume(); current = this; showing = true; String chat = getIntent().getStringExtra("chat"); ChatHeads.floating(this, chat == null ? "" : chat); }
    @Override protected void onStop() { closed(); super.onStop(); }
    @Override protected void onDestroy() { closed(); super.onDestroy(); }
    private void closed() { if (current != this) return; current = null; showing = false; ChatHeads.floating(this, ""); }

    @Override public void onBackPressed() { if (atChat()) finish(); else super.onBackPressed(); }
}
