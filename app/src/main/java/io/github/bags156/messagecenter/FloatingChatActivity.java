package io.github.bags156.messagecenter;

import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;
import java.util.List;

/**
 * A chat opened from a floating chat head: the same chat screen as a bubble's, in a rounded window
 * over whatever app is on screen. Tapping outside it, or Back, closes it and the heads return.
 * With the keyboard up, the window shrinks to end just above it (Android doesn't make room for the
 * keyboard in a floating window the way it does for a full-screen one, so the typing box hid behind it).
 */
public final class FloatingChatActivity extends PairedActivity {
    /** Whether a floating chat is on screen (the heads stay aside meanwhile). */
    static volatile boolean showing;
    /** The one on screen: an older one closing late (when another replaced it) doesn't bring the heads back. */
    private static FloatingChatActivity current;

    @Override protected boolean bubble() { return true; }

    private int width, fullHeight, height, margin;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        WindowManager wm = getSystemService(WindowManager.class);
        Rect screen = wm.getCurrentWindowMetrics().getBounds();
        android.graphics.Insets bars = wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        float density = getResources().getDisplayMetrics().density;
        margin = Math.round(8 * density);
        width = screen.width() - 2 * margin; fullHeight = height = Math.round((screen.height() - bars.top - bars.bottom) * 0.74f);
        w.setLayout(width, fullHeight);
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
        // The keyboard coming or going: fit the window above it.
        decor.setOnApplyWindowInsetsListener((v, insets) -> { v.post(this::fitKeyboard); return v.onApplyWindowInsets(insets); });
        decor.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            @Override public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> running) { return insets; }
            @Override public void onEnd(WindowInsetsAnimation animation) { fitKeyboard(); }
        });
        decor.getViewTreeObserver().addOnGlobalLayoutListener(this::fitKeyboard);
    }

    /** Where the keyboard's top edge is on screen, or -1 when it's down. */
    private int keyboardTop() {
        View decor = getWindow().getDecorView();
        WindowInsets here = decor.getRootWindowInsets();
        WindowManager wm = getSystemService(WindowManager.class);
        android.view.WindowMetrics all = wm.getCurrentWindowMetrics();
        boolean up = (here != null && here.isVisible(WindowInsets.Type.ime())) || all.getWindowInsets().isVisible(WindowInsets.Type.ime());
        if (!up) return -1;
        int fromBottom = all.getWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
        if (fromBottom > 0) return all.getBounds().bottom - fromBottom;
        int overlap = here == null ? 0 : here.getInsets(WindowInsets.Type.ime()).bottom;
        if (overlap <= 0) return -1;
        int[] at = new int[2]; decor.getLocationOnScreen(at);
        return at[1] + decor.getHeight() - overlap;
    }

    /** Ends the window just above the keyboard while it's up, and back to its full height after. */
    private void fitKeyboard() {
        if (isFinishing()) return;
        View decor = getWindow().getDecorView();
        int top = keyboardTop(), want = fullHeight;
        if (top > 0) {
            int[] at = new int[2]; decor.getLocationOnScreen(at);
            want = Math.max(Math.round(180 * getResources().getDisplayMetrics().density), Math.min(fullHeight, top - at[1] - margin));
        }
        if (want == height) return;
        height = want;
        getWindow().setLayout(width, want);
    }

    @Override protected void onResume() { super.onResume(); current = this; showing = true; String chat = getIntent().getStringExtra("chat"); ChatHeads.floating(this, chat == null ? "" : chat); }
    @Override protected void onStop() { closed(); super.onStop(); }
    @Override protected void onDestroy() { closed(); super.onDestroy(); }
    private void closed() { if (current != this) return; current = null; showing = false; ChatHeads.floating(this, ""); }

    @Override public void onBackPressed() { if (atChat()) finish(); else super.onBackPressed(); }
}
