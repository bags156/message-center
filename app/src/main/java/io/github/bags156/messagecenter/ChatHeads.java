package io.github.bags156.messagecenter;

import android.animation.ValueAnimator;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Message Center's own floating chat heads, in place of Android's bubbles (which Android draws
 * itself, at full strength). A new message shows its head in three steps, each set in Settings: at
 * first (100% for 5 seconds), then settled (50% for 2 minutes), then faded (15%) until it's
 * touched again; touching or dragging one starts the same steps over when you let go. Several heads
 * sit in one stack (the newest on top; dragged, the ones behind trail after it like Android's
 * bubbles, and settle back in when it stops); tapping the stack spreads them out, and tapping a head
 * opens its chat in a floating window (tapping elsewhere, or a few seconds untouched, stacks them
 * again). Drag to move them (they settle at the nearest side); drag the stack onto the × at the
 * bottom to close them all, or, spread out, drag one head onto it to close just that chat. Needs
 * "Display over other apps".
 */
final class ChatHeads {
    private ChatHeads() {}

    static final String STYLE = "bubble_style", HEADS = "heads";
    /** Each step's strength (percent) and, for the first two, how long it lasts (seconds). */
    static final String NEW = "head_new", NEW_SECS = "head_new_secs", MID = "head_mid", MID_SECS = "head_mid_secs", OPACITY = "head_opacity";
    static final int DEFAULT_NEW = 100, DEFAULT_NEW_SECS = 5, DEFAULT_MID = 50, DEFAULT_MID_SECS = 120, DEFAULT_OPACITY = 15, MIN_OPACITY = 10, MAX_HEADS = 5;
    /** The device checks shorten the steps (milliseconds; 0 uses the settings). */
    static long testNewMs, testMidMs;

    private static SharedPreferences paired(Context c) { return c.getSharedPreferences("paired", Context.MODE_PRIVATE); }
    private static SharedPreferences state(Context c) { return c.getSharedPreferences("chat_heads", Context.MODE_PRIVATE); }
    /** Whether chats float as these heads (true) or as Android's bubbles. */
    static boolean on(Context c) { return HEADS.equals(paired(c).getString(STYLE, "android")); }
    static boolean allowed(Context c) { return Settings.canDrawOverlays(c); }
    /** How solid the heads are at each step, in percent: 0 a new message, 1 settled, 2 faded. */
    static int strength(Context c, int stage) {
        String key = stage == 0 ? NEW : stage == 1 ? MID : OPACITY;
        int fallback = stage == 0 ? DEFAULT_NEW : stage == 1 ? DEFAULT_MID : DEFAULT_OPACITY;
        return Math.max(MIN_OPACITY, Math.min(100, paired(c).getInt(key, fallback)));
    }
    static int opacity(Context c) { return strength(c, 2); }
    /** How long the first two steps last, in seconds. */
    static int seconds(Context c, int stage) { return stage == 0 ? paired(c).getInt(NEW_SECS, DEFAULT_NEW_SECS) : paired(c).getInt(MID_SECS, DEFAULT_MID_SECS); }

    // Everything below runs on the main thread.
    private static Context app;
    private static Handler main;
    private static final List<String> chats = new ArrayList<>(); // newest first
    private static final Map<String, Integer> unread = new HashMap<>();
    private static final Map<String, Bitmap> faces = new HashMap<>();
    private static boolean loaded, touching;
    /** Spread out (true) or in one stack (false, the usual way while there's more than one). */
    private static boolean spread;
    private static final Runnable restack = () -> { if (spread && !touching) { spread = false; draw(); } };
    /** Full strength (0), half (1), faded (2). */
    private static int stage = 2;
    private static String floating = "";
    private static WindowManager wm;
    private static LinearLayout column;
    private static WindowManager.LayoutParams spot;
    private static View target, lifted;
    /** The stack as drawn (its box, and whose faces), and its faces trailing while it's dragged. */
    private static View stackBox;
    private static List<String> stacked = new ArrayList<>();
    private static Trail trail;
    private static GradientDrawable ring;
    private static ValueAnimator fading, settling;
    private static final Runnable toHalf = () -> stage(1), toFaded = () -> stage(2);

    private static Handler main() { if (main == null) main = new Handler(Looper.getMainLooper()); return main; }
    private static void ui(Context c, Runnable r) {
        if (app == null) app = c.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else main().post(r);
    }
    private static int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics())); }
    private static float strength(int stage) { return strength(app, stage) / 100f; }

    /** Goes to a step and times the next: as a new message, then settled, then faded. */
    private static void stage(int s) {
        stage = s;
        main().removeCallbacks(toHalf); main().removeCallbacks(toFaded);
        if (s == 0) main().postDelayed(toHalf, testNewMs > 0 ? testNewMs : seconds(app, 0) * 1000L);
        else if (s == 1) main().postDelayed(toFaded, testMidMs > 0 ? testMidMs : seconds(app, 1) * 1000L);
        if (!touching) fadeTo(strength(s));
    }

    /** Shows the heads at a step's strength for a moment (while that step's slider moves), then as they were. */
    static void preview(Context c, int step) {
        ui(c, () -> {
            if (column == null || touching) return;
            if (fading != null) fading.cancel();
            column.setAlpha(strength(step));
            main().removeCallbacks(back); main().postDelayed(back, 1500);
        });
    }
    private static final Runnable back = () -> { if (column != null && !touching) fadeTo(strength(stage)); };

    /** The chat open in the floating window, or "". */
    static String floatingChat() { return floating; }
    /** The chats with heads, newest first (for the device checks). */
    static List<String> heads() { return new ArrayList<>(chats); }
    /** The heads' window, when showing (for the device checks). */
    static View view() { return column; }
    /** Where the trailing faces are on screen (front first, x then y), while the stack is dragged (for the device checks). */
    static float[] trail() {
        Trail t = trail; if (t == null) return null;
        float[] out = new float[t.n * 2];
        for (int i = 0; i < t.n; i++) { out[2 * i] = t.x[i]; out[2 * i + 1] = t.y[i]; }
        return out;
    }
    /** The × to drop them on, while one is being dragged (for the device checks). */
    static View closeTarget() { return target; }

    /**
     * Whether a new message in this chat gets a head: the chats Android's bubble settings let
     * float (all of them, or the ones you picked), and any that already has one.
     */
    static boolean floats(Context c, String chat, String channel, String shortcut) {
        if (!on(c) || !allowed(c)) return false;
        if (java.util.Arrays.asList(state(c).getString("chats", "").split(",")).contains(chat)) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!nm.areBubblesEnabled()) return false;
        int pref = nm.getBubblePreference();
        if (pref == NotificationManager.BUBBLE_PREFERENCE_ALL) return true;
        if (pref != NotificationManager.BUBBLE_PREFERENCE_SELECTED || shortcut == null) return false;
        NotificationChannel ch = nm.getNotificationChannel(channel, shortcut);
        return ch != null && ch.getConversationId() != null && ch.canBubble();
    }

    /** Gives a chat a head (at the top), counting a new message; open: straight into its floating window. */
    static void add(Context c, String chat, boolean open, boolean newMessage) {
        ui(c, () -> {
            load();
            chats.remove(chat); chats.add(0, chat);
            while (chats.size() > MAX_HEADS) unread.remove(chats.remove(chats.size() - 1));
            if (newMessage && !chat.equals(floating) && !chat.equals(ChatBubbles.onScreen())) unread.merge(chat, 1, Integer::sum);
            faces.remove(chat);
            save(); draw();
            if (newMessage) stage(0);
            if (open) open(chat);
        });
    }

    /** Takes a chat's head away. */
    static void remove(Context c, String chat) { ui(c, () -> { load(); chats.remove(chat); unread.remove(chat); save(); draw(); }); }

    /** Takes every head away. */
    static void clear(Context c) { ui(c, () -> { load(); chats.clear(); unread.clear(); spread = false; save(); draw(); }); }

    /** Draws them again (a setting changed, a chat opened in the app, the app started). */
    static void refresh(Context c) { ui(c, () -> { load(); faces.clear(); unread.remove(ChatBubbles.onScreen()); draw(); }); }

    /** The floating window opened a chat (or closed: ""): heads step aside while it's open. */
    static void floating(Context c, String chat) {
        ui(c, () -> {
            load(); boolean closing = !floating.isEmpty() && (chat == null || chat.isEmpty());
            floating = chat == null ? "" : chat; if (!floating.isEmpty()) unread.remove(floating); draw();
            if (closing) stage(1); // just seen: half strength for a while, then faded
        });
    }

    /** A chat read in the app: its count goes. */
    static void read(Context c, String chat) { ui(c, () -> { if (unread.remove(chat) != null) draw(); }); }

    private static void load() {
        if (loaded) return;
        loaded = true;
        for (String id : state(app).getString("chats", "").split(",")) if (!id.isEmpty() && !chats.contains(id)) chats.add(id);
    }
    private static void save() { state(app).edit().putString("chats", String.join(",", chats)).apply(); }

    private static void draw() {
        List<String> shown = new ArrayList<>();
        for (String id : chats) if (!id.equals(ChatBubbles.onScreen())) shown.add(id);
        if (!on(app) || !allowed(app) || !floating.isEmpty() || shown.isEmpty()) { detach(); return; }
        if (column == null && !attach()) return;
        if (shown.size() == 1) spread = false;
        if (shown.size() < 2 || spread) endTrail();
        column.removeAllViews();
        if (shown.size() > 1 && !spread) column.addView(stack(shown), new LinearLayout.LayoutParams(dp(60), dp(60 + Math.min(2, shown.size() - 1) * PEEK)));
        else for (String id : shown) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(60), dp(60));
            p.topMargin = column.getChildCount() == 0 ? 0 : dp(4);
            column.addView(head(id), p);
        }
        if (fading == null || !fading.isRunning()) column.setAlpha(touching ? 1f : strength(stage));
    }

    private static boolean attach() {
        wm = app.getSystemService(WindowManager.class);
        column = new LinearLayout(app);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(4), dp(4), dp(4), dp(4));
        column.setClipToPadding(false); column.setClipChildren(false);
        spot = new WindowManager.LayoutParams(dp(68), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT);
        // Spread out, a tap anywhere else stacks them again.
        column.setOnTouchListener((v, e) -> { if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE && spread) { main().removeCallbacks(restack); spread = false; draw(); } return false; });
        column.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.setSystemGestureExclusionRects(List.of(new Rect(0, 0, r - l, b - t))));
        spot.gravity = Gravity.TOP | Gravity.START;
        spot.setTitle("Message Center chat heads");
        Rect screen = screen();
        boolean right = state(app).getBoolean("right", true);
        spot.x = right ? screen.width() - dp(68) : 0;
        spot.y = Math.round(state(app).getFloat("y", 0.28f) * screen.height());
        try { wm.addView(column, spot); return true; }
        catch (Exception e) { Diagnostics.note("Chat heads couldn't show: " + e.getClass().getSimpleName()); column = null; return false; }
    }

    private static void detach() {
        hideTarget(); dropLifted(); endTrail();
        if (column != null) { try { wm.removeView(column); } catch (Exception ignored) {} column = null; }
    }

    private static Rect screen() { return wm.getCurrentWindowMetrics().getBounds(); }
    private static android.graphics.Insets bars() { return wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); }

    /** The heads in one stack: the newest on top, two more peeking out below it, and every new message counted. */
    private static final int PEEK = 9;
    private static View stack(List<String> shown) {
        FrameLayout box = new FrameLayout(app);
        box.setClipChildren(false); box.setClipToPadding(false);
        int behind = Math.min(2, shown.size() - 1), total = 0;
        for (String id : shown) total += unread.getOrDefault(id, 0);
        for (int i = behind; i >= 0; i--) {
            ImageView face = new ImageView(app);
            face.setImageBitmap(face(shown.get(i)));
            face.setElevation(dp(3 - i));
            face.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); } });
            face.setClipToOutline(true);
            GradientDrawable edge = new GradientDrawable(); edge.setShape(GradientDrawable.OVAL); edge.setStroke(dp(2), 0xe6ffffff);
            face.setForeground(edge);
            if (i > 0) { face.setScaleX(1f - 0.1f * i); face.setScaleY(1f - 0.1f * i); face.setColorFilter(0x33000000); }
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            fp.topMargin = dp(3 + PEEK * i);
            box.addView(face, fp);
        }
        if (total > 0) {
            TextView badge = new TextView(app);
            badge.setText(total > 9 ? "9+" : String.valueOf(total));
            badge.setTextColor(Color.WHITE); badge.setTextSize(11); badge.setGravity(Gravity.CENTER); badge.setIncludeFontPadding(false);
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(0xffe11d48); dot.setStroke(dp(2), Color.WHITE);
            badge.setBackground(dot); badge.setElevation(dp(5));
            box.addView(badge, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END));
        }
        box.setContentDescription("Floating chats: " + shown.size() + " chats" + (total > 0 ? ", " + total + " new" : "") + ". Tap to show them, or drag to move.");
        box.setOnClickListener(v -> { spread = true; draw(); main().removeCallbacks(restack); main().postDelayed(restack, 6000); });
        box.setOnTouchListener(new Drag(shown.get(0)));
        stackBox = box; stacked = new ArrayList<>(shown.subList(0, behind + 1));
        if (trail != null) { box.setAlpha(0f); trail.box = box; }
        return box;
    }

    private static View head(String id) {
        FrameLayout box = new FrameLayout(app);
        box.setClipChildren(false); box.setClipToPadding(false);
        ImageView face = new ImageView(app);
        face.setImageBitmap(face(id));
        face.setElevation(dp(3));
        face.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); } });
        face.setClipToOutline(true);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER);
        box.addView(face, fp);
        int count = unread.getOrDefault(id, 0);
        if (count > 0) {
            TextView badge = new TextView(app);
            badge.setText(count > 9 ? "9+" : String.valueOf(count));
            badge.setTextColor(Color.WHITE); badge.setTextSize(11); badge.setGravity(Gravity.CENTER); badge.setIncludeFontPadding(false);
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(0xffe11d48); dot.setStroke(dp(2), Color.WHITE);
            badge.setBackground(dot); badge.setElevation(dp(4));
            box.addView(badge, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END));
        }
        String name = title(id);
        box.setContentDescription("Floating chat: " + name + (count > 0 ? ", " + count + " new" : "") + ". Tap to open, or drag to move.");
        box.setOnClickListener(v -> open(id));
        box.setOnTouchListener(new Drag(id));
        return box;
    }

    /**
     * Dragging the stack (or a lone head) moves them all; letting go settles them at the nearer
     * side, or on the × closes them. Spread out, dragging a head lifts just that one: on the × it
     * closes that chat; anywhere else it goes back into line.
     */
    private static final class Drag implements View.OnTouchListener {
        final String id; float downX, downY; int startX, startY; boolean dragging, over;
        WindowManager.LayoutParams lift;
        Drag(String id) { this.id = id; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            if (column == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY(); startX = spot.x; startY = spot.y; dragging = false; over = false; lift = null;
                    touching = true; main().removeCallbacks(toHalf); main().removeCallbacks(toFaded); main().removeCallbacks(restack); fadeTo(1f);
                    if (settling != null) settling.cancel();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(app).getScaledTouchSlop()) {
                        dragging = true; showTarget();
                        if (spread && column.getChildCount() > 1) lift = lift(v);
                        else if (v == stackBox && stacked.size() > 1) startTrail(v);
                    }
                    if (!dragging) return true;
                    Rect s = screen();
                    if (lift != null) {
                        lift.x = startX + Math.round(dx); lift.y = startY + Math.round(dy);
                        try { wm.updateViewLayout(lifted, lift); } catch (Exception ignored) {}
                    } else {
                        spot.x = Math.max(-dp(8), Math.min(s.width() - dp(60), startX + Math.round(dx)));
                        spot.y = Math.max(0, Math.min(s.height() - dp(68), startY + Math.round(dy)));
                        try { wm.updateViewLayout(column, spot); } catch (Exception ignored) {}
                    }
                    boolean was = over;
                    over = onTarget(e.getRawX(), e.getRawY());
                    if (trail != null) trail.over = over;
                    if (over != was) {
                        if (targetFace != null) { targetFace.animate().scaleX(over ? 1.25f : 1f).scaleY(over ? 1.25f : 1f).setDuration(120).start(); }
                        if (ring != null) ring.setColor(over ? 0xeee11d48 : 0xcc111827);
                        if (over) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    touching = false;
                    boolean click = !dragging && e.getActionMasked() == MotionEvent.ACTION_UP;
                    hideTarget();
                    if (trail != null) { if (!(dragging && over)) trail.over = false; trail.kick(); main().removeCallbacks(trailWatch); main().postDelayed(trailWatch, 1500); }
                    if (lift != null) {
                        lift = null; dropLifted();
                        if (over) { main().post(() -> remove(app, id)); return true; }
                        v.setAlpha(1f);
                        stage(0); main().postDelayed(restack, 6000);
                        return true;
                    }
                    if (dragging && over) { main().post(() -> clear(app)); return true; }
                    if (dragging) settle();
                    stage(0);
                    if (spread && !click) main().postDelayed(restack, 6000);
                    if (click) v.performClick();
                    return true;
                }
                default: return false;
            }
        }

        /** Takes the head out of line into a window of its own that follows the finger. */
        private WindowManager.LayoutParams lift(View v) {
            int[] col = new int[2], at = new int[2];
            column.getLocationOnScreen(col); v.getLocationOnScreen(at);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(v.getWidth(), v.getHeight(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            // The same offset Android puts between these windows' positions and the screen.
            p.x = spot.x + at[0] - col[0]; p.y = spot.y + at[1] - col[1];
            p.setTitle("Message Center chat head");
            View face = head(id); face.setOnTouchListener(null); face.setOnClickListener(null); face.setClickable(false);
            dropLifted();
            try { wm.addView(face, p); } catch (Exception e) { return null; }
            lifted = face; v.setAlpha(0f);
            startX = p.x; startY = p.y;
            return p;
        }
    }

    /**
     * While the stack is dragged, its faces are drawn in a window over the screen (one that takes
     * no touches): the front one stays with the finger, and each one behind springs after the one
     * ahead of it, so they trail like Android's bubbles. Over the ×, they're pulled onto it. Let
     * go, they follow the stack to the side and settle back into it, and the stack shows again.
     */
    private static final class Trail implements Choreographer.FrameCallback {
        final int n; final float[] x, y, vx, vy; final View[] faces; final View badge;
        final FrameLayout room; final int offX, offY; View box; boolean over; long last;
        Trail(View box) {
            this.box = box;
            n = stacked.size();
            x = new float[n]; y = new float[n]; vx = new float[n]; vy = new float[n]; faces = new View[n];
            int[] at = new int[2]; box.getLocationOnScreen(at);
            // Where the front face sits against the stack's window position (Android offsets these windows from the screen).
            offX = at[0] - spot.x + dp(3); offY = at[1] - spot.y + dp(3);
            room = new FrameLayout(app); room.setClipChildren(false);
            int total = 0;
            for (String id : stacked) total += unread.getOrDefault(id, 0);
            for (int i = n - 1; i >= 0; i--) {
                ImageView face = new ImageView(app);
                face.setImageBitmap(face(stacked.get(i)));
                face.setElevation(dp(3 - i));
                face.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); } });
                face.setClipToOutline(true);
                GradientDrawable edge = new GradientDrawable(); edge.setShape(GradientDrawable.OVAL); edge.setStroke(dp(2), 0xe6ffffff);
                face.setForeground(edge);
                if (i > 0) { face.setScaleX(1f - 0.1f * i); face.setScaleY(1f - 0.1f * i); face.setColorFilter(0x33000000); }
                room.addView(face, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP | Gravity.START));
                faces[i] = face;
                x[i] = at[0] + dp(3); y[i] = at[1] + dp(3 + PEEK * i);
            }
            if (total > 0) {
                TextView b = new TextView(app);
                b.setText(total > 9 ? "9+" : String.valueOf(total));
                b.setTextColor(Color.WHITE); b.setTextSize(11); b.setGravity(Gravity.CENTER); b.setIncludeFontPadding(false);
                GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(0xffe11d48); dot.setStroke(dp(2), Color.WHITE);
                b.setBackground(dot); b.setElevation(dp(5));
                room.addView(b, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.START));
                badge = b;
            } else badge = null;
            place();
        }

        /** Where the front face is headed: with the stack, or onto the ×. */
        private float[] lead() {
            if (over && target != null && target.getWidth() > 0) {
                int[] t = new int[2]; target.getLocationOnScreen(t);
                return new float[] { t[0] + target.getWidth() / 2f - dp(27), t[1] + target.getHeight() / 2f - dp(27) };
            }
            return new float[] { spot.x + offX, spot.y + offY };
        }

        @Override public void doFrame(long nanos) {
            if (trail != this) return;
            float dt = last == 0 ? 0.016f : Math.min(0.034f, (nanos - last) / 1e9f); last = nanos;
            // Small steps: the front one's spring is stiff, and big steps make it fly apart.
            int steps = Math.max(1, (int) Math.ceil(dt / 0.002f)); float h = dt / steps;
            boolean still = true;
            for (int k = 0; k < steps; k++) {
                float[] to = lead();
                for (int i = 0; i < n; i++) {
                    float tx = i == 0 ? to[0] : x[i - 1], ty = i == 0 ? to[1] : y[i - 1] + dp(PEEK);
                    // The front one stays with the finger (and is pulled more gently onto the ×); the ones
                    // behind are looser, so they lag and swing in.
                    float w = i == 0 ? (over ? 40f : 140f) : 24f, z = i == 0 ? 1f : 0.72f;
                    vx[i] += (w * w * (tx - x[i]) - 2 * z * w * vx[i]) * h; x[i] += vx[i] * h;
                    vy[i] += (w * w * (ty - y[i]) - 2 * z * w * vy[i]) * h; y[i] += vy[i] * h;
                    if (!Float.isFinite(x[i]) || !Float.isFinite(y[i]) || !Float.isFinite(vx[i]) || !Float.isFinite(vy[i])) { x[i] = tx; y[i] = ty; vx[i] = 0; vy[i] = 0; }
                    if (k == steps - 1 && (Math.abs(tx - x[i]) > 0.5f || Math.abs(ty - y[i]) > 0.5f || Math.hypot(vx[i], vy[i]) > 20)) still = false;
                }
            }
            place();
            if (still && !touching && (settling == null || !settling.isRunning())) { endTrail(); return; }
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void place() {
            int[] o = new int[2]; room.getLocationOnScreen(o);
            for (int i = 0; i < n; i++) { faces[i].setTranslationX(x[i] - o[0]); faces[i].setTranslationY(y[i] - o[1]); }
            if (badge != null) { badge.setTranslationX(x[0] + dp(35) - o[0]); badge.setTranslationY(y[0] - dp(3) - o[1]); }
            room.setAlpha(column != null ? column.getAlpha() : 1f);
        }

        void kick() { Choreographer.getInstance().removeFrameCallback(this); Choreographer.getInstance().postFrameCallback(this); }
    }

    private static final Runnable trailWatch = ChatHeads::endTrail;

    private static void startTrail(View box) {
        endTrail();
        Trail t = new Trail(box);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("Message Center chat heads, moving");
        try { wm.addView(t.room, p); } catch (Exception e) { return; }
        trail = t;
        // The stack hides once its trailing faces are drawn in the same place (no blank frame between).
        t.room.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
            boolean done;
            @Override public void onDraw() { if (done) return; done = true; t.room.post(() -> { if (trail == t && t.box != null) t.box.setAlpha(0f); t.place(); }); }
        });
        t.kick();
    }

    /** Puts the stack back as it was, and takes the trailing faces away. */
    private static void endTrail() {
        Trail t = trail; if (t == null) return;
        trail = null;
        main().removeCallbacks(trailWatch);
        Choreographer.getInstance().removeFrameCallback(t);
        if (t.box != null) t.box.setAlpha(1f);
        try { wm.removeView(t.room); } catch (Exception ignored) {}
    }

    private static void dropLifted() {
        if (lifted == null) return;
        try { wm.removeView(lifted); } catch (Exception ignored) {}
        lifted = null;
    }

    /** To the nearer side, kept clear of the status and navigation bars, and remembered. */
    private static void settle() {
        Rect s = screen(); android.graphics.Insets bars = bars();
        boolean right = spot.x + dp(34) > s.width() / 2;
        int fromX = spot.x, toX = right ? s.width() - dp(68) : 0;
        int height = column.getHeight() > 0 ? column.getHeight() : dp(68);
        spot.y = Math.max(bars.top + dp(8), Math.min(s.height() - bars.bottom - height - dp(8), spot.y));
        state(app).edit().putBoolean("right", right).putFloat("y", spot.y / (float) s.height()).apply();
        settling = ValueAnimator.ofInt(fromX, toX).setDuration(220);
        settling.addUpdateListener(a -> { if (column == null) return; spot.x = (int) a.getAnimatedValue(); try { wm.updateViewLayout(column, spot); } catch (Exception ignored) {} });
        settling.start();
    }

    private static View targetFace;
    private static void showTarget() {
        if (target != null) return;
        TextView x = new TextView(app);
        x.setText("✕"); x.setTextColor(Color.WHITE); x.setTextSize(24); x.setGravity(Gravity.CENTER);
        ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setColor(0xcc111827); ring.setStroke(dp(2), 0x99ffffff);
        x.setBackground(ring);
        FrameLayout room = new FrameLayout(app);
        room.addView(x, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        room.setContentDescription("Drop here to close the floating chats");
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(88), dp(88), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = bars().bottom + dp(48 - 12);
        p.alpha = 0.8f; // never so solid that Android stops touches passing to the app beneath
        p.setTitle("Message Center close target");
        target = room; targetFace = x;
        try { wm.addView(target, p); } catch (Exception e) { target = null; targetFace = null; }
    }

    private static void hideTarget() {
        if (target == null) return;
        try { wm.removeView(target); } catch (Exception ignored) {}
        target = null; targetFace = null; ring = null;
    }

    /** Whether the finger is over the ×, going by where the × really is on screen. */
    private static boolean onTarget(float x, float y) {
        if (target == null) return false;
        int[] at = new int[2]; target.getLocationOnScreen(at);
        int w = target.getWidth() > 0 ? target.getWidth() : dp(88), h = target.getHeight() > 0 ? target.getHeight() : dp(88);
        if (target.getWidth() == 0) { Rect s = screen(); at[0] = s.width() / 2 - w / 2; at[1] = s.height() - bars().bottom - dp(36) - h; }
        return Math.hypot(x - (at[0] + w / 2f), y - (at[1] + h / 2f)) < dp(80);
    }

    private static void fadeTo(float alpha) {
        if (column == null) return;
        if (fading != null) fading.cancel();
        View v = column;
        fading = ValueAnimator.ofFloat(v.getAlpha(), alpha).setDuration(alpha >= v.getAlpha() ? 120 : 600);
        fading.addUpdateListener(a -> v.setAlpha((float) a.getAnimatedValue()));
        fading.start();
    }

    /** Opens the chat in the floating window, over whatever's on screen. */
    private static void open(String id) {
        unread.remove(id); spread = false; main().removeCallbacks(restack);
        floating = id; draw();
        Intent i = new Intent(app, FloatingChatActivity.class).setAction(Intent.ACTION_VIEW)
            .setData(new Uri.Builder().scheme("messagecenter").authority("chat").appendPath(id).build()).putExtra("chat", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try { app.startActivity(i); }
        catch (Exception e) { Diagnostics.note("Floating chat couldn't open: " + e.getClass().getSimpleName()); floating = ""; draw(); return; }
        // If Android didn't let it open, the heads come back rather than staying hidden.
        main().postDelayed(() -> { if (id.equals(floating) && !FloatingChatActivity.showing) { floating = ""; draw(); } }, 4000);
    }

    private static String title(String id) {
        PairedStore store = PairedService.sharedStore();
        JSONObject chat = store == null ? null : store.chat(id);
        return chat == null ? "Chat" : PairedProtocol.title(chat);
    }

    private static Bitmap face(String id) {
        Bitmap had = faces.get(id);
        if (had != null) return had;
        int size = dp(54);
        PairedStore store = PairedService.sharedStore();
        JSONObject chat = store == null ? null : store.chat(id);
        String title = chat == null ? "?" : PairedProtocol.title(chat);
        Bitmap out = null;
        try {
            if (chat != null && Avatars.isGroup(chat)) out = Avatars.circle(Avatars.group(app, chat, size), size);
            else {
                JSONObject other = chat == null ? null : PairedProtocol.other(chat);
                String pid = other == null ? "" : PairedProtocol.participantID(other);
                if (!pid.isEmpty()) { File f = Avatars.best(app, pid); if (f.isFile()) { Bitmap b = BitmapFactory.decodeFile(f.getPath()); if (b != null) out = Avatars.circle(b, size); } }
                if (out == null) {
                    String hex = other != null ? other.optString("avatarHexColor", "") : chat == null ? "" : chat.optString("avatarHexColor", "");
                    out = Avatars.initial(PairedProtocol.initial(title), PairedProtocol.color(hex, Avatars.fallbackColor(title)), size);
                }
            }
        } catch (Exception e) {
            out = Avatars.initial("?", 0xff64748b, size);
        }
        faces.put(id, out);
        return out;
    }
}
