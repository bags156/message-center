package io.github.bags156.messagecenter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.util.Random;
import java.util.function.Supplier;

/**
 * Plays one full-screen message effect over the chat, then removes itself. It never takes
 * touches, so the chat keeps scrolling underneath. Everything is drawn from the time since it
 * started, so a slow frame only skips ahead instead of slowing the effect down.
 */
final class EffectView extends View {
    private static final int[] FESTIVE = {0xffff4f6d, 0xffffb02e, 0xff3ddc84, 0xff4f8bff, 0xffb36bff, 0xffff7ac6, 0xff2ad4d4, 0xffffe14d};
    private static final int[] GOLD = {0xffffd76a, 0xffffe9a8, 0xfff5b83d, 0xffffffff, 0xffffc94a};
    private static final int[] BEAMS = {0xffff3b5c, 0xff3dff8a, 0xff4f8bff, 0xffff4fe1, 0xff43f0ff};

    private final String effect;
    private final float d;
    private final long start = SystemClock.uptimeMillis();
    private final long duration;
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    /** Where the message is on screen, relative to this view (null if it scrolled away or isn't drawn). */
    private final Supplier<RectF> target;
    private final String text;
    private final int bubbleColor, inkColor;
    private float[][] parts;

    EffectView(Context context, String effect, Supplier<RectF> target, String text, int bubbleColor, int inkColor) {
        super(context);
        this.effect = effect; this.target = target; this.text = text == null ? "" : text;
        this.bubbleColor = bubbleColor; this.inkColor = inkColor;
        d = context.getResources().getDisplayMetrics().density;
        duration = Effects.BALLOONS.equals(effect) ? 4600 : Effects.FIREWORKS.equals(effect) ? 3800
            : Effects.CONFETTI.equals(effect) ? 4000 : Effects.LASERS.equals(effect) ? 3200 : 3000;
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND);
        setClickable(false); setFocusable(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setContentDescription(Effects.label(effect));
    }

    /** Stops early, as when you leave the chat. */
    void stop() { ViewGroup p = (ViewGroup) getParent(); if (p != null) p.removeView(this); }

    @Override protected void onDraw(Canvas c) {
        long t = SystemClock.uptimeMillis() - start;
        if (t >= duration) { post(this::stop); return; }
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) { postInvalidateOnAnimation(); return; }
        if (parts == null) parts = setUp(w, h);
        float s = t / 1000f;
        switch (effect) {
            case Effects.BALLOONS: balloons(c, s, w, h); break;
            case Effects.CONFETTI: confetti(c, s, w, h); break;
            case Effects.FIREWORKS: fireworks(c, s, w, h); break;
            case Effects.CELEBRATION: celebration(c, s, w, h); break;
            case Effects.LOVE: love(c, s, w, h); break;
            case Effects.LASERS: lasers(c, s, w, h); break;
            case Effects.SHOOTING_STAR: shootingStar(c, s, w, h); break;
            case Effects.ECHO: echo(c, s, w, h); break;
            case Effects.SPOTLIGHT: spotlight(c, s, w, h); break;
            default: break;
        }
        postInvalidateOnAnimation();
    }

    private float r(float a, float b) { return a + random.nextFloat() * (b - a); }

    private float[][] setUp(int w, int h) {
        float[][] p;
        switch (effect) {
            case Effects.BALLOONS:
                // x, start delay (s), speed (screens/s), radius, color, sway phase
                p = new float[16][];
                for (int i = 0; i < p.length; i++) p[i] = new float[]{r(0.06f, 0.94f) * w, r(0f, 1.6f), r(0.32f, 0.46f), r(26, 38) * d, FESTIVE[i % FESTIVE.length], r(0f, 6.3f)};
                return p;
            case Effects.CONFETTI:
                // x, start y, fall speed (px/s), drift phase, spin speed, width, height, color, shape
                p = new float[150][];
                for (int i = 0; i < p.length; i++) p[i] = new float[]{r(0, w), -r(0, h * 0.9f) - 20 * d, r(0.24f, 0.42f) * h, r(0, 6.3f), r(2f, 7f), r(5, 8) * d, r(9, 14) * d, FESTIVE[random.nextInt(FESTIVE.length)], random.nextInt(4)};
                return p;
            case Effects.FIREWORKS:
                // per burst: launch time, x, y, color, size
                p = new float[6][];
                for (int i = 0; i < p.length; i++) p[i] = new float[]{0.15f + i * 0.45f + r(0, 0.15f), r(0.18f, 0.82f) * w, r(0.16f, 0.5f) * h, FESTIVE[random.nextInt(FESTIVE.length)], r(110, 165) * d};
                return p;
            case Effects.CELEBRATION:
                // birth time, angle, speed, size, color, twinkle phase
                p = new float[170][];
                for (int i = 0; i < p.length; i++) p[i] = new float[]{r(0, 1.9f), r(1.75f, 3.1f), r(0.35f, 0.95f) * Math.max(w, h), r(1.6f, 3.6f) * d, GOLD[random.nextInt(GOLD.length)], r(0, 6.3f)};
                return p;
            case Effects.LOVE:
                // little hearts: x offset, delay, speed, size, sway phase
                p = new float[10][];
                for (int i = 0; i < p.length; i++) p[i] = new float[]{r(-0.4f, 0.4f) * w, r(0.4f, 1.4f), r(0.18f, 0.32f) * h, r(10, 20) * d, r(0, 6.3f)};
                return p;
            case Effects.SHOOTING_STAR:
                // sparks at the end: angle, speed, size, color
                p = new float[60][];
                for (int i = 0; i < p.length; i++) p[i] = new float[]{r(0, 6.3f), r(60, 260) * d, r(1.5f, 3.5f) * d, GOLD[random.nextInt(GOLD.length)]};
                return p;
            case Effects.ECHO:
                // y, size (sp-ish), speed (px/s), delay, direction
                p = new float[26][];
                for (int i = 0; i < p.length; i++) p[i] = new float[]{r(0.04f, 0.92f) * h, r(13, 30) * d, r(0.45f, 1.1f) * w, r(0, 1.3f), random.nextBoolean() ? 1 : -1};
                return p;
            default:
                return new float[0][];
        }
    }

    /** 0→1 over the first in seconds and 1→0 over the last out seconds. */
    private float envelope(float s, float in, float out) {
        float total = duration / 1000f;
        return Math.max(0f, Math.min(1f, Math.min(in <= 0 ? 1 : s / in, out <= 0 ? 1 : (total - s) / out)));
    }

    private void dim(Canvas c, float amount) { if (amount > 0) c.drawColor(((int) (amount * 255) << 24)); }

    private void balloons(Canvas c, float s, int w, int h) {
        float fade = envelope(s, 0, 0.6f);
        for (float[] b : parts) {
            float age = s - b[1]; if (age < 0) continue;
            float radius = b[3], cy = h + radius * 1.4f - age * b[2] * h, cx = b[0] + (float) Math.sin(age * 1.7f + b[5]) * 14 * d;
            if (cy < -radius * 4) continue;
            int color = (int) b[4], a = (int) (235 * fade);
            // string
            stroke.setStrokeWidth(1.2f * d); stroke.setColor((a << 24) | 0x00bdbdbd);
            path.reset(); float knot = cy + radius * 1.18f;
            path.moveTo(cx, knot);
            path.cubicTo(cx - 8 * d, knot + radius * 0.8f, cx + 8 * d, knot + radius * 1.5f, cx - 2 * d, knot + radius * 2.4f);
            c.drawPath(path, stroke);
            // knot
            paint.setColor((a << 24) | (color & 0xffffff));
            path.reset(); path.moveTo(cx, knot - 2 * d); path.lineTo(cx - 5 * d, knot + 5 * d); path.lineTo(cx + 5 * d, knot + 5 * d); path.close(); c.drawPath(path, paint);
            // balloon and its shine
            oval.set(cx - radius, cy - radius * 1.18f, cx + radius, cy + radius * 1.18f); c.drawOval(oval, paint);
            paint.setColor(((int) (a * 0.45f) << 24) | 0x00ffffff);
            oval.set(cx - radius * 0.55f, cy - radius * 0.85f, cx - radius * 0.15f, cy - radius * 0.25f); c.drawOval(oval, paint);
        }
    }

    private void confetti(Canvas c, float s, int w, int h) {
        float fade = envelope(s, 0, 0.7f);
        for (float[] p : parts) {
            float y = p[1] + s * p[2]; if (y > h + 20 * d || y < -40 * d) continue;
            float x = p[0] + (float) Math.sin(s * 2.2f + p[3]) * 22 * d;
            paint.setColor(((int) (255 * fade) << 24) | ((int) p[7] & 0xffffff));
            c.save(); c.translate(x, y); c.rotate((s * p[4] * 57.3f + p[3] * 57.3f) % 360);
            // the flip of a falling piece of paper
            c.scale((float) Math.cos(s * p[4] + p[3]), 1f);
            float pw = p[5], ph = p[6];
            if (p[8] == 0) c.drawCircle(0, 0, pw * 0.6f, paint);
            else c.drawRect(-pw / 2, -ph / 2, pw / 2, ph / 2, paint);
            c.restore();
        }
    }

    private void fireworks(Canvas c, float s, int w, int h) {
        dim(c, 0.55f * envelope(s, 0.35f, 0.6f));
        for (float[] b : parts) {
            float launch = b[0], cx = b[1], cy = b[2], size = b[4]; int color = (int) b[3];
            float age = s - launch; if (age < 0) continue;
            float rise = 0.45f;
            if (age < rise) {
                // the rocket going up
                float q = age / rise, y = h - (h - cy) * (1 - (1 - q) * (1 - q));
                paint.setColor(0xfffff2c0); c.drawCircle(cx, y, 2.4f * d, paint);
                stroke.setStrokeWidth(1.6f * d); stroke.setColor(0x66fff2c0); c.drawLine(cx, y, cx, y + 26 * d, stroke);
                continue;
            }
            float e = age - rise, life = 1.5f; if (e > life) continue;
            float spread = 1 - (1 - Math.min(1, e / 0.9f)) * (1 - Math.min(1, e / 0.9f));
            float drop = e * e * 55 * d, alpha = Math.max(0, 1 - e / life);
            int rays = 46;
            for (int k = 0; k < rays; k++) {
                double ang = k * Math.PI * 2 / rays + launch;
                float reach = size * (0.75f + 0.25f * (float) Math.sin(k * 7.1f + launch * 3));
                float x = cx + (float) Math.cos(ang) * reach * spread, y = cy + (float) Math.sin(ang) * reach * spread + drop;
                float tx = cx + (float) Math.cos(ang) * reach * spread * 0.82f, ty = cy + (float) Math.sin(ang) * reach * spread * 0.82f + drop;
                stroke.setStrokeWidth(2.2f * d); stroke.setColor(((int) (alpha * 160) << 24) | (color & 0xffffff)); c.drawLine(tx, ty, x, y, stroke);
                // sparkle at the tip
                boolean twinkle = ((int) (e * 18 + k)) % 3 != 0;
                paint.setColor(((int) (alpha * (twinkle ? 255 : 120)) << 24) | 0x00ffffff); c.drawCircle(x, y, 1.6f * d, paint);
            }
        }
    }

    private void celebration(Canvas c, float s, int w, int h) {
        float fade = envelope(s, 0, 0.6f);
        for (float[] p : parts) {
            float age = s - p[0]; if (age < 0 || age > 1.6f) continue;
            float dist = p[2] * (1 - (1 - age / 1.6f) * (1 - age / 1.6f));
            // From the top right corner, spraying down and to the left.
            float x = w + (float) Math.cos(p[1]) * dist, y = (float) Math.sin(p[1]) * dist + age * age * 30 * d;
            float tw = 0.55f + 0.45f * (float) Math.sin(age * 22 + p[5]);
            int a = (int) (255 * fade * tw * Math.max(0, 1 - age / 1.6f));
            paint.setColor((a << 24) | ((int) p[4] & 0xffffff));
            star(c, x, y, p[3] * 2.2f, p[3] * 0.7f);
        }
    }

    /** A four-pointed sparkle. */
    private void star(Canvas c, float x, float y, float outer, float inner) {
        path.reset();
        for (int k = 0; k < 8; k++) {
            double ang = k * Math.PI / 4; float rr = k % 2 == 0 ? outer : inner;
            float px = x + (float) Math.cos(ang) * rr, py = y + (float) Math.sin(ang) * rr;
            if (k == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close(); c.drawPath(path, paint);
    }

    private void heart(Canvas c, float cx, float cy, float size) {
        path.reset();
        path.moveTo(cx, cy + size * 0.35f);
        path.cubicTo(cx - size * 1.1f, cy - size * 0.35f, cx - size * 0.55f, cy - size * 1.05f, cx, cy - size * 0.45f);
        path.cubicTo(cx + size * 0.55f, cy - size * 1.05f, cx + size * 1.1f, cy - size * 0.35f, cx, cy + size * 0.35f);
        path.close(); c.drawPath(path, paint);
    }

    private void love(Canvas c, float s, int w, int h) {
        RectF at = target.get();
        float cx = at != null ? at.centerX() : w / 2f, cy = at != null ? at.top : h * 0.6f;
        float grow = Math.min(1, s / 0.9f), pulse = 1 + 0.06f * (float) Math.sin(s * 9);
        float size = (40 + 70 * (1 - (1 - grow) * (1 - grow))) * d * pulse;
        float rise = Math.max(0, s - 1.2f) * 0.35f * h;
        int a = (int) (240 * envelope(s, 0.2f, 0.9f));
        paint.setColor((a << 24) | 0x00ff3b6b);
        heart(c, cx, cy - size * 0.2f - rise, size);
        paint.setColor(((int) (a * 0.35f) << 24) | 0x00ffffff);
        oval.set(cx - size * 0.62f, cy - size * 0.95f - rise, cx - size * 0.28f, cy - size * 0.6f - rise); c.drawOval(oval, paint);
        for (float[] p : parts) {
            float age = s - p[1]; if (age < 0) continue;
            float x = cx + p[0] * 0.6f + (float) Math.sin(age * 2.4f + p[4]) * 10 * d, y = cy - age * p[2];
            paint.setColor(((int) (a * Math.max(0, 1 - age / 1.8f)) << 24) | 0x00ff6f91);
            heart(c, x, y, p[3]);
        }
    }

    private void lasers(Canvas c, float s, int w, int h) {
        float env = envelope(s, 0.4f, 0.7f);
        dim(c, 0.62f * env);
        RectF at = target.get();
        float cx = w / 2f, cy = at != null ? at.centerY() : h * 0.55f;
        float reach = (float) Math.hypot(w, h);
        for (int k = 0; k < 10; k++) {
            int color = BEAMS[k % BEAMS.length];
            // beams sweep slowly; nothing flashes
            double ang = k * Math.PI / 5 + s * (k % 2 == 0 ? 0.9 : -0.7);
            float ex = cx + (float) Math.cos(ang) * reach, ey = cy + (float) Math.sin(ang) * reach;
            stroke.setColor(((int) (70 * env) << 24) | (color & 0xffffff)); stroke.setStrokeWidth(14 * d); c.drawLine(cx, cy, ex, ey, stroke);
            stroke.setColor(((int) (230 * env) << 24) | (color & 0xffffff)); stroke.setStrokeWidth(3 * d); c.drawLine(cx, cy, ex, ey, stroke);
        }
        paint.setColor(((int) (255 * env) << 24) | 0x00ffffff); c.drawCircle(cx, cy, 5 * d, paint);
    }

    private void shootingStar(Canvas c, float s, int w, int h) {
        dim(c, 0.35f * envelope(s, 0.3f, 0.6f));
        float travel = 1.3f, q = Math.min(1, s / travel);
        float sx = -30 * d, sy = h * 0.18f, ex = w * 0.78f, ey = h * 0.42f;
        // along a gentle arc
        for (int k = 14; k >= 0; k--) {
            float tq = Math.max(0, q - k * 0.018f);
            float x = sx + (ex - sx) * tq, y = sy + (ey - sy) * tq - (float) Math.sin(tq * Math.PI) * h * 0.08f;
            paint.setColor(((int) (255 * (1 - k / 15f) * (q < 1 ? 1 : Math.max(0, 1 - (s - travel) * 3))) << 24) | 0x00fff4c4);
            c.drawCircle(x, y, (6 - k * 0.32f) * d, paint);
        }
        float x = sx + (ex - sx) * q, y = sy + (ey - sy) * q - (float) Math.sin(q * Math.PI) * h * 0.08f;
        if (q < 1) { paint.setColor(0xffffffff); star(c, x, y, 13 * d, 4 * d); return; }
        float e = s - travel, life = 1.4f; if (e > life) return;
        for (float[] p : parts) {
            float dist = p[1] * (1 - (1 - e / life) * (1 - e / life));
            float px = ex + (float) Math.cos(p[0]) * dist, py = ey + (float) Math.sin(p[0]) * dist + e * e * 40 * d;
            paint.setColor(((int) (255 * Math.max(0, 1 - e / life)) << 24) | ((int) p[3] & 0xffffff));
            star(c, px, py, p[2] * 2, p[2] * 0.6f);
        }
    }

    private void echo(Canvas c, float s, int w, int h) {
        String line = text.isEmpty() ? "✨" : text.length() > 40 ? text.substring(0, 39) + "…" : text;
        float fade = envelope(s, 0.2f, 0.6f);
        for (float[] p : parts) {
            float age = s - p[3]; if (age < 0) continue;
            paint.setTextSize(p[1]);
            float tw = paint.measureText(line), padX = p[1] * 0.7f, padY = p[1] * 0.45f, bw = tw + padX * 2, bh = p[1] + padY * 2;
            float x = p[4] > 0 ? -bw + age * p[2] : w - age * p[2];
            if (x > w || x < -bw) continue;
            int a = (int) (225 * fade);
            paint.setColor((a << 24) | (bubbleColor & 0xffffff));
            oval.set(x, p[0], x + bw, p[0] + bh); c.drawRoundRect(oval, bh / 2, bh / 2, paint);
            paint.setColor((a << 24) | (inkColor & 0xffffff));
            c.drawText(line, x + padX, p[0] + padY + p[1] * 0.82f, paint);
        }
    }

    private void spotlight(Canvas c, float s, int w, int h) {
        RectF at = target.get();
        float env = envelope(s, 0.45f, 0.6f);
        float cx = at != null ? at.centerX() : w / 2f, cy = at != null ? at.centerY() : h / 2f;
        float radius = at != null ? Math.max(at.width(), at.height()) * 0.62f + 24 * d : 120 * d;
        radius *= 0.85f + 0.15f * Math.min(1, s / 0.6f);
        int edge = (int) (215 * env) << 24;
        paint.setShader(new RadialGradient(cx, cy, radius * 1.25f, new int[]{0x00000000, 0x00000000, edge}, new float[]{0f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }
}
