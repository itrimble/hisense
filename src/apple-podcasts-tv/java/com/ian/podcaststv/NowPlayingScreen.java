package com.ian.podcaststv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.text.DateFormat;
import java.util.Date;
import java.util.Random;
import java.util.function.Supplier;

/**
 * The screensaver while audio plays, like Apple TV's Now Playing screen: the artwork, the
 * episode, its chapter and progress, over a dark wash of the artwork's colours. The whole
 * composition drifts to a new spot every minute. Drawn on a 1920x1080 design canvas.
 */
final class NowPlayingScreen extends View {
    private static final float W = 1920, H = 1080, ART = 520;
    private static final long DRIFT_MS = 60_000;

    private final Supplier<NowPlaying> now;
    private final Supplier<Bitmap> art;
    private final Supplier<String> chapter;
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Matrix m = new Matrix();
    private Bitmap wash, washOf;
    private float dx, dy;
    private long shownAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidate();
            if (SystemClock.uptimeMillis() - shownAt > DRIFT_MS) drift();
            postDelayed(this, 1000);
        }
    };

    NowPlayingScreen(Context c, Supplier<NowPlaying> now, Supplier<Bitmap> art, Supplier<String> chapter) {
        super(c);
        this.now = now;
        this.art = art;
        this.chapter = chapter;
        setVisibility(GONE);
        setBackgroundColor(Color.BLACK);
    }

    boolean isShowing() { return getVisibility() == VISIBLE; }

    void show() {
        if (isShowing()) return;
        dx = dy = 0;
        shownAt = SystemClock.uptimeMillis();
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(900);
        removeCallbacks(tick);
        post(tick);
    }

    void hide() {
        if (!isShowing()) return;
        removeCallbacks(tick);
        animate().cancel();
        setVisibility(GONE);
    }

    /** A new spot every minute, so nothing sits still on the panel. */
    private void drift() {
        shownAt = SystemClock.uptimeMillis();
        float fx = dx, fy = dy, tx = (random.nextFloat() - 0.5f) * 220, ty = (random.nextFloat() - 0.5f) * 140;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(4000);
        a.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            dx = fx + (tx - fx) * f;
            dy = fy + (ty - fy) * f;
            invalidate();
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        NowPlaying n = now.get();
        if (n == null) return;
        float k = getWidth() / W;
        c.save();
        c.scale(k, k);
        Bitmap b = art.get();
        drawWash(c, b);
        c.translate(dx, dy);

        // Artwork, with a soft shadow.
        float ax = 190, ay = (H - ART) / 2;
        r.set(ax, ay, ax + ART, ay + ART);
        paint.setShader(null);
        paint.setColor(0xFF000000);
        paint.setShadowLayer(50, 0, 24, 0xAA000000);
        c.drawRoundRect(r, 18, 18, paint);
        paint.clearShadowLayer();
        if (b != null) {
            BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            m.setScale(ART / b.getWidth(), ART / b.getHeight());
            m.postTranslate(ax, ay);
            s.setLocalMatrix(m);
            paint.setShader(s);
            c.drawRoundRect(r, 18, 18, paint);
            paint.setShader(null);
        }

        // Words and progress, to the right of the artwork.
        float x = ax + ART + 90, w = W - x - 170, y = ay + 40;
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(30);
        text.setColor(0xB3FFFFFF);
        c.drawText(TextUtils.ellipsize(n.show, text, w, TextUtils.TruncateAt.END).toString(), x, y, text);
        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        text.setTextSize(58);
        text.setColor(0xFFFFFFFF);
        StaticLayout title = StaticLayout.Builder.obtain(n.title, 0, n.title.length(), text, (int) w)
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.05f)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        c.save();
        c.translate(x, y + 24);
        title.draw(c);
        c.restore();
        y += 24 + title.getHeight() + 56;
        String ch = chapter.get();
        if (ch != null) {
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextSize(32);
            text.setColor(0xE6FFFFFF);
            c.drawText(TextUtils.ellipsize(ch, text, w, TextUtils.TruncateAt.END).toString(), x, y, text);
            y += 56;
        }
        double pos = n.now();
        float f = n.duration > 0 ? (float) Math.min(1, pos / n.duration) : 0;
        r.set(x, y, x + w, y + 8);
        paint.setColor(0x40FFFFFF);
        c.drawRoundRect(r, 4, 4, paint);
        r.right = x + w * f;
        paint.setColor(0xE6FFFFFF);
        c.drawRoundRect(r, 4, 4, paint);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(26);
        text.setColor(0x99FFFFFF);
        c.drawText(Chapters.clock(pos), x, y + 48, text);
        String right = n.playing ? "−" + Chapters.clock(Math.max(0, n.duration - pos)) : "Paused";
        c.drawText(right, x + w - text.measureText(right), y + 48, text);
        c.restore();

        // Clock, top right, like the rest of the TV.
        c.save();
        c.scale(k, k);
        String t = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        text.setTextSize(34);
        text.setColor(0xB3FFFFFF);
        c.drawText(t, W - 96 - text.measureText(t), 96, text);
        c.restore();
    }

    /** The artwork blown up from a few pixels: a soft wash of its colours, then darkened. */
    private void drawWash(Canvas c, Bitmap b) {
        if (b != null && b != washOf) {
            wash = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(b, 8, 8, true), 64, 36, true);
            washOf = b;
        }
        if (wash != null) {
            paint.setShader(null);
            c.drawBitmap(wash, new Rect(0, 0, wash.getWidth(), wash.getHeight()), new RectF(-120, -120, W + 120, H + 120), paint);
        }
        paint.setShader(new LinearGradient(0, 0, W, 0, 0xCC000000, 0x99000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);
    }
}
