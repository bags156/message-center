package io.github.bags156.messagecenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Marks up a photo before it's sent: draw on it, add text, or crop it. Nothing changes the
 * original; Done saves an edited copy (JPEG) and hands it back.
 */
final class MarkupEditor {
    interface Done { void done(File edited); }

    private static final ExecutorService work = Executors.newSingleThreadExecutor();
    private static final int[] COLORS = {0xFFE53935, 0xFFFFFFFF, 0xFF000000, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA};
    private static final String DRAW = "Draw", TEXT = "Text", CROP = "Crop";

    /** Opens the editor for a photo file; done gets the edited copy. */
    static void open(Activity a, File source, Done done) {
        work.execute(() -> {
            Bitmap decoded = MediaCache.decode(source, 2560, false);
            // A software copy: the decoder may hand back a cached or GPU-only bitmap.
            Bitmap base = decoded == null ? null : decoded.copy(Bitmap.Config.ARGB_8888, false);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (base == null) { Toast.makeText(a, "This photo can't be edited.", Toast.LENGTH_LONG).show(); return; }
                new MarkupEditor(a, base, done).show();
            });
        });
    }

    private static final class Stroke {
        final Path path = new Path(); final int color; final float width;
        Stroke(int color, float width) { this.color = color; this.width = width; }
    }
    private static final class Label {
        String text; float x, y; int color; final float size;
        Label(String text, float x, float y, int color, float size) { this.text = text; this.x = x; this.y = y; this.color = color; this.size = size; }
    }

    private final Activity a;
    private final Bitmap base;
    private final Done done;
    private final Dialog dialog;
    private final Board board;
    /** Strokes and labels in drawing order. */
    private final List<Object> marks = new ArrayList<>();
    /** One entry per change, newest last; running it takes that change back. */
    private final List<Runnable> undo = new ArrayList<>();
    /** The part of the photo kept, in photo pixels. */
    private final RectF crop;
    private final List<TextView> modeButtons = new ArrayList<>();
    private final List<View> swatches = new ArrayList<>();
    private String mode = DRAW;
    private int color = COLORS[0];
    private Label selected;
    private boolean saving;

    private MarkupEditor(Activity a, Bitmap base, Done done) {
        this.a = a; this.base = base; this.done = done;
        crop = new RectF(0, 0, base.getWidth(), base.getHeight());
        dialog = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);
        board = new Board(a);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -1);
        bl.topMargin = dp(64); bl.bottomMargin = dp(150);
        root.addView(board, bl);

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(12), dp(12), dp(8));
        top.addView(button("Cancel", false, this::close));
        View space = new View(a);
        top.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(button("Undo", false, this::undoLast));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.setMarginStart(dp(8));
        top.addView(button("Done", true, this::save), dl);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), dp(8), dp(12), dp(28));
        LinearLayout colors = new LinearLayout(a);
        colors.setGravity(Gravity.CENTER);
        for (int c : COLORS) {
            View swatch = new View(a);
            swatch.setTag(c);
            swatch.setContentDescription(colorName(c));
            swatch.setOnClickListener(v -> pickColor(c));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(30), dp(30));
            sl.setMargins(dp(7), 0, dp(7), 0);
            colors.addView(swatch, sl);
            swatches.add(swatch);
        }
        bottom.addView(colors);
        LinearLayout modes = new LinearLayout(a);
        modes.setGravity(Gravity.CENTER);
        modes.setPadding(0, dp(14), 0, 0);
        for (String m : new String[]{DRAW, TEXT, CROP}) {
            TextView t = button(m, false, () -> setMode(m));
            t.setTag(m);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
            ml.setMargins(dp(6), 0, dp(6), 0);
            modes.addView(t, ml);
            modeButtons.add(t);
        }
        bottom.addView(modes);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        dialog.setContentView(root);
        dialog.setOnKeyListener((d, key, e) -> {
            if (key == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) { close(); return true; }
            return key == KeyEvent.KEYCODE_BACK;
        });
        refreshControls();
    }

    /** The editor on screen, for the on-device test. */
    static MarkupEditor shown;
    private void show() { shown = this; dialog.setOnDismissListener(d -> { if (shown == this) shown = null; }); dialog.show(); }

    // ---- Controls ----

    private void setMode(String m) {
        if (!m.equals(mode)) selected = null;
        mode = m;
        refreshControls();
        board.invalidate();
        // Choosing Text with no words on the photo yet starts one in the middle of what's kept.
        boolean any = false;
        for (Object o : marks) if (o instanceof Label) any = true;
        if (m.equals(TEXT) && !any) addLabel(crop.centerX(), crop.centerY());
    }

    private void pickColor(int c) {
        color = c;
        // In Text, the color also applies to the label being moved.
        if (mode.equals(TEXT) && selected != null && selected.color != c) {
            Label l = selected; int before = l.color;
            l.color = c;
            undo.add(() -> l.color = before);
        }
        refreshControls();
        board.invalidate();
    }

    private void refreshControls() {
        for (TextView t : modeButtons) {
            boolean on = mode.equals(t.getTag());
            t.setBackground(pill(on ? 0xFFFFFFFF : 0x33FFFFFF));
            t.setTextColor(on ? Color.BLACK : Color.WHITE);
        }
        for (View s : swatches) {
            int c = (Integer) s.getTag();
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(c);
            d.setStroke(dp(c == color ? 3 : 1), c == color ? 0xFFFFFFFF : 0x66FFFFFF);
            s.setBackground(d);
            s.setScaleX(c == color ? 1.15f : 1f);
            s.setScaleY(c == color ? 1.15f : 1f);
            s.setVisibility(mode.equals(CROP) ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private void undoLast() {
        if (undo.isEmpty()) return;
        undo.remove(undo.size() - 1).run();
        if (selected != null && !marks.contains(selected)) selected = null;
        board.invalidate();
    }

    private boolean changed() { return !undo.isEmpty(); }

    private void close() {
        if (saving) return;
        if (!changed()) { dialog.dismiss(); return; }
        new AlertDialog.Builder(a).setTitle("Discard your changes?")
            .setPositiveButton("Discard", (d, w) -> dialog.dismiss())
            .setNegativeButton("Keep editing", null).show();
    }

    private void addLabel(float x, float y) { editLabel(null, x, y); }

    /** Asks for a label's words: a new one at (x, y), or a change to an existing one (empty removes it). */
    private void editLabel(Label existing, float x, float y) {
        EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        if (existing != null) { input.setText(existing.text); input.setSelection(input.length()); }
        FrameLayout box = new FrameLayout(a);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(existing == null ? "Add text" : "Edit text").setView(box)
            .setPositiveButton(existing == null ? "Add" : "Save", (d, w) -> {
                String words = input.getText().toString().trim();
                if (existing == null) {
                    if (words.isEmpty()) return;
                    Label l = new Label(words, x, y, color, Math.min(base.getWidth(), base.getHeight()) * 0.08f);
                    marks.add(l); selected = l;
                    undo.add(() -> marks.remove(l));
                } else if (words.isEmpty()) {
                    removeLabel(existing);
                } else if (!words.equals(existing.text)) {
                    String before = existing.text;
                    existing.text = words;
                    undo.add(() -> existing.text = before);
                }
                board.invalidate();
            })
            .setNegativeButton("Cancel", null);
        if (existing != null) b.setNeutralButton("Remove", (d, w) -> { removeLabel(existing); board.invalidate(); });
        AlertDialog shown = b.show();
        input.requestFocus();
        if (shown.getWindow() != null) shown.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private void removeLabel(Label l) {
        int at = marks.indexOf(l);
        if (at < 0) return;
        marks.remove(at);
        if (selected == l) selected = null;
        undo.add(() -> marks.add(Math.min(at, marks.size()), l));
    }

    /** Draws the marked-up photo, cropped, and saves it as a new JPEG. */
    private void save() {
        if (saving) return;
        if (!changed()) { dialog.dismiss(); return; }
        saving = true;
        Bitmap full = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(full);
        c.drawColor(Color.WHITE);
        c.drawBitmap(base, 0, 0, null);
        drawMarks(c);
        int left = clamp(Math.round(crop.left), 0, base.getWidth() - 1), topEdge = clamp(Math.round(crop.top), 0, base.getHeight() - 1);
        int width = clamp(Math.round(crop.width()), 1, base.getWidth() - left), height = clamp(Math.round(crop.height()), 1, base.getHeight() - topEdge);
        Bitmap out = width == base.getWidth() && height == base.getHeight() ? full : Bitmap.createBitmap(full, left, topEdge, width, height);
        work.execute(() -> {
            File dir = new File(a.getCacheDir(), "outgoing");
            if (!dir.isDirectory()) dir.mkdirs();
            File file = new File(dir, UUID.randomUUID() + "-edited.jpg");
            boolean ok;
            try (FileOutputStream f = new FileOutputStream(file)) { ok = out.compress(Bitmap.CompressFormat.JPEG, 92, f); }
            catch (Exception e) { ok = false; }
            boolean saved = ok;
            a.runOnUiThread(() -> {
                saving = false;
                if (!saved) { file.delete(); Toast.makeText(a, "The edited photo couldn't be saved.", Toast.LENGTH_LONG).show(); return; }
                dialog.dismiss();
                done.done(file);
            });
        });
    }

    // ---- Drawing ----

    private void drawMarks(Canvas c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (Object o : marks) {
            if (o instanceof Stroke) {
                Stroke s = (Stroke) o;
                p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
                p.setStrokeWidth(s.width); p.setColor(s.color);
                c.drawPath(s.path, p);
            } else drawLabel(c, (Label) o, p);
        }
    }

    /** Bold words with an outline in black or white, whichever stands out from their color. */
    private static void drawLabel(Canvas c, Label l, Paint p) {
        p.setTextSize(l.size); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextAlign(Paint.Align.CENTER);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(l.size * 0.14f); p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(Color.luminance(l.color) > 0.5f ? Color.BLACK : Color.WHITE);
        c.drawText(l.text, l.x, l.y, p);
        p.setStyle(Paint.Style.FILL); p.setColor(l.color);
        c.drawText(l.text, l.x, l.y, p);
    }

    private static RectF labelBounds(Label l) {
        Paint p = new Paint();
        p.setTextSize(l.size); p.setTypeface(Typeface.DEFAULT_BOLD);
        float half = p.measureText(l.text) / 2f + l.size * 0.2f;
        return new RectF(l.x - half, l.y - l.size * 1.05f, l.x + half, l.y + l.size * 0.35f);
    }

    /** The photo, fitted to the screen, with the marks and (in Crop, or once cropped) the crop frame. */
    private final class Board extends View {
        private final Matrix toScreen = new Matrix(), toPhoto = new Matrix();
        private final Paint dim = new Paint(), frame = new Paint(Paint.ANTI_ALIAS_FLAG), handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Stroke drawing;
        private Label dragging;
        private float lastX, lastY, downX, downY;
        private boolean moved;
        private int cropHandle = -1; // 0-3 corners (TL, TR, BR, BL), 4 the whole frame
        private RectF cropBefore;
        private float labelX, labelY;

        Board(Activity context) {
            super(context);
            dim.setColor(0xB3000000);
            frame.setStyle(Paint.Style.STROKE); frame.setColor(Color.WHITE);
            handle.setColor(Color.WHITE);
            setContentDescription("Photo to mark up");
        }

        private float scale() { return Math.min(getWidth() / (float) base.getWidth(), getHeight() / (float) base.getHeight()); }

        @Override protected void onDraw(Canvas c) {
            float s = scale();
            if (s <= 0) return;
            toScreen.setScale(s, s);
            toScreen.postTranslate((getWidth() - base.getWidth() * s) / 2f, (getHeight() - base.getHeight() * s) / 2f);
            toScreen.invert(toPhoto);
            c.save();
            c.concat(toScreen);
            c.drawBitmap(base, 0, 0, null);
            drawMarks(c);
            boolean cropping = mode.equals(CROP);
            float w = base.getWidth(), h = base.getHeight();
            if (cropping || crop.left > 0.5f || crop.top > 0.5f || crop.right < w - 0.5f || crop.bottom < h - 0.5f) {
                c.drawRect(0, 0, w, crop.top, dim);
                c.drawRect(0, crop.bottom, w, h, dim);
                c.drawRect(0, crop.top, crop.left, crop.bottom, dim);
                c.drawRect(crop.right, crop.top, w, crop.bottom, dim);
            }
            if (cropping) {
                frame.setStrokeWidth(dp(2) / s);
                c.drawRect(crop, frame);
                float r = dp(7) / s;
                c.drawCircle(crop.left, crop.top, r, handle); c.drawCircle(crop.right, crop.top, r, handle);
                c.drawCircle(crop.right, crop.bottom, r, handle); c.drawCircle(crop.left, crop.bottom, r, handle);
            }
            if (mode.equals(TEXT) && selected != null && marks.contains(selected)) {
                frame.setStrokeWidth(dp(1.5f) / s);
                c.drawRoundRect(labelBounds(selected), dp(6) / s, dp(6) / s, frame);
            }
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (saving) return true;
            float[] pt = {e.getX(), e.getY()};
            toPhoto.mapPoints(pt);
            float x = pt[0], y = pt[1];
            switch (mode) {
                case DRAW -> draw(e, x, y);
                case TEXT -> text(e, x, y);
                default -> crop(e, x, y);
            }
            invalidate();
            return true;
        }

        private void draw(MotionEvent e, float x, float y) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    drawing = new Stroke(color, Math.max(base.getWidth(), base.getHeight()) * 0.008f);
                    drawing.path.moveTo(x, y);
                    drawing.path.lineTo(x + 0.1f, y);
                    marks.add(drawing);
                    lastX = x; lastY = y;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (drawing == null) return;
                    drawing.path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f);
                    lastX = x; lastY = y;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (drawing == null) return;
                    drawing.path.lineTo(x, y);
                    Stroke finished = drawing;
                    drawing = null;
                    undo.add(() -> marks.remove(finished));
                }
                default -> { }
            }
        }

        private void text(MotionEvent e, float x, float y) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    dragging = null; moved = false; downX = e.getX(); downY = e.getY();
                    for (int i = marks.size() - 1; i >= 0; i--) {
                        if (marks.get(i) instanceof Label && labelBounds((Label) marks.get(i)).contains(x, y)) { dragging = (Label) marks.get(i); break; }
                    }
                    if (dragging != null) { selected = dragging; labelX = dragging.x; labelY = dragging.y; }
                    lastX = x; lastY = y;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > dp(6)) moved = true;
                    if (dragging == null || !moved) return;
                    dragging.x += x - lastX; dragging.y += y - lastY;
                    lastX = x; lastY = y;
                }
                case MotionEvent.ACTION_UP -> {
                    Label l = dragging;
                    dragging = null;
                    if (l != null && moved) { float bx = labelX, by = labelY; undo.add(() -> { l.x = bx; l.y = by; }); }
                    else if (l != null) editLabel(l, l.x, l.y);
                    else if (!moved) addLabel(x, y);
                }
                case MotionEvent.ACTION_CANCEL -> { if (dragging != null && moved) { dragging.x = labelX; dragging.y = labelY; } dragging = null; }
                default -> { }
            }
        }

        private void crop(MotionEvent e, float x, float y) {
            float w = base.getWidth(), h = base.getHeight(), min = Math.min(w, h) * 0.1f, reach = dp(32) / scale();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    float[][] corners = {{crop.left, crop.top}, {crop.right, crop.top}, {crop.right, crop.bottom}, {crop.left, crop.bottom}};
                    cropHandle = -1;
                    float best = reach;
                    for (int i = 0; i < 4; i++) {
                        float d = (float) Math.hypot(x - corners[i][0], y - corners[i][1]);
                        if (d <= best) { best = d; cropHandle = i; }
                    }
                    if (cropHandle < 0 && crop.contains(x, y)) cropHandle = 4;
                    cropBefore = cropHandle >= 0 ? new RectF(crop) : null;
                    lastX = x; lastY = y;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (cropHandle < 0) return;
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x; lastY = y;
                    if (cropHandle == 4) {
                        float mx = Math.max(-crop.left, Math.min(w - crop.right, dx)), my = Math.max(-crop.top, Math.min(h - crop.bottom, dy));
                        crop.offset(mx, my);
                    } else {
                        if (cropHandle == 0 || cropHandle == 3) crop.left = Math.max(0, Math.min(crop.right - min, crop.left + dx));
                        if (cropHandle == 1 || cropHandle == 2) crop.right = Math.min(w, Math.max(crop.left + min, crop.right + dx));
                        if (cropHandle == 0 || cropHandle == 1) crop.top = Math.max(0, Math.min(crop.bottom - min, crop.top + dy));
                        if (cropHandle == 2 || cropHandle == 3) crop.bottom = Math.min(h, Math.max(crop.top + min, crop.bottom + dy));
                    }
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (cropBefore != null && !cropBefore.equals(crop)) { RectF before = cropBefore; undo.add(() -> crop.set(before)); }
                    cropHandle = -1; cropBefore = null;
                }
                default -> { }
            }
        }
    }

    // ---- Small helpers ----

    private TextView button(String label, boolean primary, Runnable action) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(primary ? Color.BLACK : Color.WHITE);
        t.setPadding(dp(16), dp(9), dp(16), dp(9));
        t.setBackground(pill(primary ? 0xFFFFFFFF : 0x33FFFFFF));
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private GradientDrawable pill(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(20));
        return d;
    }

    private static String colorName(int c) {
        switch (c) {
            case 0xFFE53935: return "Red";
            case 0xFFFFFFFF: return "White";
            case 0xFF000000: return "Black";
            case 0xFFFDD835: return "Yellow";
            case 0xFF43A047: return "Green";
            case 0xFF1E88E5: return "Blue";
            default: return "Purple";
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics())); }
}
