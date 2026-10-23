package com.ian.tvhome;

import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;

import java.text.DateFormat;
import java.util.Date;

/** Everything drawn above or around the grid: top shelf, clock, context menu, hints. */
final class Overlay {
    private static final float PAD = 96;

    private final HomeView v;
    final NowPlayingCard nowPlaying;
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hero = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();
    private final RectF r = new RectF();

    private Apps.App heroFrom, heroTo;
    private final Spring heroFade = new Spring(0.35f, 1f, 1);

    private String[] items;
    private boolean open;
    private int sel;
    private final Spring menu = new Spring(0.28f, 0.86f, 0);
    private String toast;
    private long toastUntil;
    private final Spring toastIn = new Spring(0.3f, 1f, 0);

    Overlay(HomeView v) {
        this.v = v;
        this.nowPlaying = new NowPlayingCard(v);
        title.setTypeface(v.fonts.display);
        title.setColor(0xFFFFFFFF);
        title.setTextSize(78);
        title.setLetterSpacing(-0.02f);
        body.setTypeface(v.fonts.text);
        body.setColor(0xFFFFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f);
        shadow.setColor(0xFF000000);
    }

    // ---- state -------------------------------------------------------------------

    void heroNow(Apps.App a) { heroFrom = null; heroTo = a; heroFade.snap(1); }

    void hero(Apps.App a) {
        if (a == heroTo) return;
        heroFrom = heroTo;
        heroTo = a;
        heroFade.snap(0);
        heroFade.to(1);
    }

    boolean step(float dt) {
        boolean busy = heroFade.step(dt) | menu.step(dt) | toastIn.step(dt);
        if (toast != null && SystemClock.uptimeMillis() > toastUntil) { toastIn.to(0); toastUntil = Long.MAX_VALUE; }
        return busy || (toast != null && toastIn.target > 0);
    }

    void openMenu(String[] options) { items = options; open = true; sel = 0; menu.to(1); v.kick(); }

    void closeMenu() { open = false; menu.to(0); v.kick(); }

    boolean menuOpen() { return open; }

    String menuChoice() { return open ? items[sel] : null; }

    boolean menuStep(int d) {
        if (!open) return false;
        int n = Math.max(0, Math.min(items.length - 1, sel + d));
        boolean moved = n != sel;
        sel = n;
        v.kick();
        return moved;
    }

    void toast(String text) {
        toast = text;
        toastUntil = SystemClock.uptimeMillis() + 2600;
        toastIn.to(1);
        v.kick();
    }

    // ---- drawing ------------------------------------------------------------------

    void drawShelf(Canvas c) {
        float a = v.shelf.value;
        if (a < 0.01f) return;
        c.save();
        c.scale(v.k, v.k);
        c.translate(0, -v.scroll.value * 0.35f);
        float f = Math.max(0, Math.min(1, heroFade.value));
        if (heroFrom != null && f < 1) drawHero(c, heroFrom, a * (1 - f));
        if (heroTo != null) drawHero(c, heroTo, a * f);
        nowPlaying.draw(c, a);
        c.restore();
    }

    private void drawHero(Canvas c, Apps.App app, float alpha) {
        if (alpha < 0.01f || app.hero == null) return;
        float x = HomeView.W - PAD - HomeView.HERO_W, y = 150;
        r.set(x, y, x + HomeView.HERO_W, y + HomeView.HERO_H);
        shadow.setAlpha(Math.round(200 * alpha));
        shadow.setShadowLayer(60, 0, 30, (int) (170 * alpha) << 24);
        c.drawRoundRect(r, 30, 30, shadow);
        BitmapShader s = new BitmapShader(app.hero, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        m.setScale(HomeView.HERO_W / app.hero.getWidth(), HomeView.HERO_H / app.hero.getHeight());
        m.postTranslate(x, y);
        s.setLocalMatrix(m);
        hero.setShader(s);
        hero.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(r, 30, 30, hero);
        rim.setShader(new LinearGradient(0, y, 0, y + HomeView.HERO_H, (int) (70 * alpha) << 24 | 0xFFFFFF, 0,
                Shader.TileMode.CLAMP));
        r.inset(0.75f, 0.75f);
        c.drawRoundRect(r, 30, 30, rim);

        float maxW = x - PAD * 2;
        title.setAlpha(Math.round(255 * alpha));
        String t = TextUtils.ellipsize(app.label, title, maxW, TextUtils.TruncateAt.END).toString();
        c.drawText(t, PAD, y + 150, title);
        body.setTextSize(27);
        body.setAlpha(Math.round(150 * alpha));
        c.drawText("Press select to open  ·  Hold for options", PAD, y + 205, body);
    }

    void drawChrome(Canvas c) {
        c.save();
        c.scale(v.k, v.k);
        drawClock(c);
        if (v.moveMode) capsule(c, "Move with the arrows  ·  Press select when done", HomeView.H - 70, 1);
        float ta = Math.max(0, Math.min(1, toastIn.value));
        if (toast != null && ta > 0.01f) capsule(c, toast, HomeView.H - 70 - (v.moveMode ? 80 : 0), ta);
        if (toast != null && toastIn.target == 0 && ta < 0.01f) toast = null;
        drawMenu(c);
        c.restore();
    }

    private void drawClock(Canvas c) {
        String now = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        body.setTypeface(v.fonts.semibold);
        body.setTextSize(30);
        float w = body.measureText(now) + 48;
        r.set(HomeView.W - PAD - w, 44, HomeView.W - PAD, 100);
        glassPanel(c, r, 28, 1, 0x33FFFFFF);
        body.setAlpha(235);
        c.drawText(now, r.left + 24, r.centerY() + 11, body);
        body.setTypeface(v.fonts.text);
    }

    private void capsule(Canvas c, String text, float y, float alpha) {
        body.setTextSize(27);
        float w = body.measureText(text) + 64;
        r.set((HomeView.W - w) / 2, y - 30 + (1 - alpha) * 20, (HomeView.W + w) / 2, y + 30 + (1 - alpha) * 20);
        glassPanel(c, r, 30, alpha, 0x2EFFFFFF);
        body.setAlpha(Math.round(240 * alpha));
        c.drawText(text, r.left + 32, r.centerY() + 10, body);
    }

    private void drawMenu(Canvas c) {
        float a = menu.value;
        if (a < 0.01f || v.focused() == null) return;
        String[] list = items != null ? items : new String[0];
        if (list.length == 0) return;
        HomeView.Tile t = v.tiles.get(v.focused());
        if (t == null) return;
        float itemH = 72, w = 460, h = list.length * itemH + 20;
        float tx = t.x.value + HomeView.TILE_W / 2, ty = t.y.value - v.scroll.value;
        boolean above = ty + HomeView.TILE_H + h + 60 > HomeView.H;
        float left = Math.max(40, Math.min(HomeView.W - 40 - w, tx - w / 2));
        float top = above ? ty - h - 34 : ty + HomeView.TILE_H + 34;
        int save = c.save();
        c.scale(0.9f + 0.1f * a, 0.9f + 0.1f * a, tx, above ? top + h : top); // grows out of the tile
        r.set(left, top, left + w, top + h);
        glassPanel(c, r, 26, Math.min(1, a), 0x24FFFFFF);
        body.setTypeface(v.fonts.medium);
        body.setTextSize(29);
        for (int i = 0; i < list.length; i++) {
            float iy = top + 10 + i * itemH;
            if (i == sel) {
                r.set(left + 10, iy, left + w - 10, iy + itemH);
                glass.setShader(null);
                glass.setColor(0xFFF2F2F7);
                glass.setAlpha(Math.round(245 * a));
                c.drawRoundRect(r, 18, 18, glass);
                body.setColor(0xFF1C1C1E);
            } else {
                body.setColor(0xFFFFFFFF);
            }
            body.setAlpha(Math.round(255 * a));
            c.drawText(list[i], left + 34, iy + itemH / 2 + 10, body);
        }
        body.setColor(0xFFFFFFFF);
        body.setTypeface(v.fonts.text);
        c.restoreToCount(save);
    }

    /** A frosted capsule: translucent fill, soft shadow, and a bright top rim catching the light. */
    private void glassPanel(Canvas c, RectF rr, float radius, float alpha, int tint) {
        shadow.setAlpha(Math.round(120 * alpha));
        shadow.setShadowLayer(30, 0, 12, (int) (110 * alpha) << 24);
        glass.setShader(null);
        glass.setColor(0xFF2C2C30);
        glass.setAlpha(Math.round(200 * alpha));
        c.drawRoundRect(rr, radius, radius, shadow);
        c.drawRoundRect(rr, radius, radius, glass);
        glass.setShader(new LinearGradient(0, rr.top, 0, rr.bottom, tint, 0x00FFFFFF, Shader.TileMode.CLAMP));
        glass.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(rr, radius, radius, glass);
        rim.setShader(new LinearGradient(0, rr.top, 0, rr.bottom, (int) (110 * alpha) << 24 | 0xFFFFFF,
                (int) (18 * alpha) << 24 | 0xFFFFFF, Shader.TileMode.CLAMP));
        RectF in = new RectF(rr);
        in.inset(0.75f, 0.75f);
        c.drawRoundRect(in, radius, radius, rim);
    }
}
