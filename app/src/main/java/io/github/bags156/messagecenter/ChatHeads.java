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
import android.view.Gravity;
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
 * itself, at full strength). They sit see-through at the edge of the screen, at the strength you
 * choose; touching one, or a new message, brings it up to full until it's left alone again. Tap
 * one to open its chat in a floating window; drag to move them (they settle at the nearest side);
 * drag onto the × at the bottom to close them. Needs "Display over other apps".
 */
final class ChatHeads {
    private ChatHeads() {}

    static final String STYLE = "bubble_style", HEADS = "heads", OPACITY = "head_opacity";
    static final int DEFAULT_OPACITY = 50, MIN_OPACITY = 15, MAX_HEADS = 5;

    private static SharedPreferences paired(Context c) { return c.getSharedPreferences("paired", Context.MODE_PRIVATE); }
    private static SharedPreferences state(Context c) { return c.getSharedPreferences("chat_heads", Context.MODE_PRIVATE); }
    /** Whether chats float as these heads (true) or as Android's bubbles. */
    static boolean on(Context c) { return HEADS.equals(paired(c).getString(STYLE, "android")); }
    static boolean allowed(Context c) { return Settings.canDrawOverlays(c); }
    /** How solid the heads are while left alone, in percent. */
    static int opacity(Context c) { return Math.max(MIN_OPACITY, Math.min(100, paired(c).getInt(OPACITY, DEFAULT_OPACITY))); }

    // Everything below runs on the main thread.
    private static Context app;
    private static Handler main;
    private static final List<String> chats = new ArrayList<>(); // newest first
    private static final Map<String, Integer> unread = new HashMap<>();
    private static final Map<String, Bitmap> faces = new HashMap<>();
    private static boolean loaded, touching, lit;
    private static String floating = "";
    private static WindowManager wm;
    private static LinearLayout column;
    private static WindowManager.LayoutParams spot;
    private static View target;
    private static ValueAnimator fading, settling;
    private static final Runnable dim = () -> { lit = false; fadeTo(idle()); };

    private static Handler main() { if (main == null) main = new Handler(Looper.getMainLooper()); return main; }
    private static void ui(Context c, Runnable r) {
        if (app == null) app = c.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else main().post(r);
    }
    private static int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics())); }
    private static float idle() { return opacity(app) / 100f; }

    /** The chat open in the floating window, or "". */
    static String floatingChat() { return floating; }
    /** The chats with heads, newest first (for the device checks). */
    static List<String> heads() { return new ArrayList<>(chats); }
    /** The heads' window, when showing (for the device checks). */
    static View view() { return column; }

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
            if (newMessage) light(3000);
            if (open) open(chat);
        });
    }

    /** Takes a chat's head away. */
    static void remove(Context c, String chat) { ui(c, () -> { load(); chats.remove(chat); unread.remove(chat); save(); draw(); }); }

    /** Takes every head away. */
    static void clear(Context c) { ui(c, () -> { load(); chats.clear(); unread.clear(); save(); draw(); }); }

    /** Draws them again (a setting changed, a chat opened in the app, the app started). */
    static void refresh(Context c) { ui(c, () -> { load(); faces.clear(); unread.remove(ChatBubbles.onScreen()); draw(); }); }

    /** The floating window opened a chat (or closed: ""): heads step aside while it's open. */
    static void floating(Context c, String chat) {
        ui(c, () -> { load(); floating = chat == null ? "" : chat; if (!floating.isEmpty()) unread.remove(floating); draw(); });
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
        column.removeAllViews();
        for (String id : shown) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(60), dp(60));
            p.topMargin = column.getChildCount() == 0 ? 0 : dp(4);
            column.addView(head(id), p);
        }
        if (fading == null || !fading.isRunning()) column.setAlpha(touching || lit ? 1f : idle());
    }

    private static boolean attach() {
        wm = app.getSystemService(WindowManager.class);
        column = new LinearLayout(app);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(4), dp(4), dp(4), dp(4));
        column.setClipToPadding(false); column.setClipChildren(false);
        spot = new WindowManager.LayoutParams(dp(68), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
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
        hideTarget();
        if (column != null) { try { wm.removeView(column); } catch (Exception ignored) {} column = null; }
    }

    private static Rect screen() { return wm.getCurrentWindowMetrics().getBounds(); }
    private static android.graphics.Insets bars() { return wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); }

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

    /** Dragging a head moves them all; letting go settles them at the nearer side, or on the × closes them. */
    private static final class Drag implements View.OnTouchListener {
        final String id; float downX, downY; int startX, startY; boolean dragging, over;
        Drag(String id) { this.id = id; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            if (column == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY(); startX = spot.x; startY = spot.y; dragging = false; over = false;
                    touching = true; main().removeCallbacks(dim); fadeTo(1f);
                    if (settling != null) settling.cancel();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(app).getScaledTouchSlop()) { dragging = true; showTarget(); }
                    if (!dragging) return true;
                    Rect s = screen();
                    spot.x = Math.max(-dp(8), Math.min(s.width() - dp(60), startX + Math.round(dx)));
                    spot.y = Math.max(0, Math.min(s.height() - dp(68), startY + Math.round(dy)));
                    over = nearTarget();
                    if (target != null) { target.setScaleX(over ? 1.25f : 1f); target.setScaleY(over ? 1.25f : 1f); }
                    try { wm.updateViewLayout(column, spot); } catch (Exception ignored) {}
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    touching = false;
                    boolean click = !dragging && e.getActionMasked() == MotionEvent.ACTION_UP;
                    hideTarget();
                    if (dragging && over) { clear(app); return true; }
                    if (dragging) settle();
                    main().postDelayed(dim, 1500);
                    if (click) v.performClick();
                    return true;
                }
                default: return false;
            }
        }
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

    private static void showTarget() {
        if (target != null) return;
        TextView x = new TextView(app);
        x.setText("✕"); x.setTextColor(Color.WHITE); x.setTextSize(24); x.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setColor(0xcc111827); ring.setStroke(dp(2), 0x99ffffff);
        x.setBackground(ring);
        x.setContentDescription("Drop here to close the floating chats");
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(64), dp(64), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = bars().bottom + dp(48);
        p.alpha = 0.8f; // never so solid that Android stops touches passing to the app beneath
        p.setTitle("Message Center close target");
        target = x;
        try { wm.addView(target, p); } catch (Exception e) { target = null; }
    }

    private static void hideTarget() {
        if (target == null) return;
        try { wm.removeView(target); } catch (Exception ignored) {}
        target = null;
    }

    private static boolean nearTarget() {
        Rect s = screen();
        float hx = spot.x + dp(34), hy = spot.y + dp(34);
        float tx = s.width() / 2f, ty = s.height() - bars().bottom - dp(48) - dp(32);
        return Math.hypot(hx - tx, hy - ty) < dp(84);
    }

    /** Up to full strength for a while (a new message), then back to see-through. */
    private static void light(long ms) {
        if (column == null) return;
        lit = true; main().removeCallbacks(dim); fadeTo(1f);
        if (!touching) main().postDelayed(dim, ms);
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
        unread.remove(id);
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
