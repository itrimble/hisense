package com.ian.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole home screen, drawn by hand: ambient backdrop, top shelf, the app grid and
 * overlays. Everything that moves is a {@link Spring}, so focus, scrolling and
 * reordering can all be interrupted mid-flight. Layout is designed on a 1920x1080
 * canvas and scaled by {@link #k}.
 */
final class HomeView extends View implements Choreographer.FrameCallback {
    static final int COLS = 5;
    static final float W = 1920, H = 1080;
    static final float MARGIN = 90, GAP = 45, TILE_W = 312, TILE_H = 187, CORNER = 22;
    static final float ROW_PITCH = TILE_H + 96;
    static final float TOP_ROW_Y = H - TILE_H - 128;   // focus on the top row: shelf above
    static final float LOWER_ROW_Y = 330;              // focus below: the focused row sits here
    static final float HERO_W = 820, HERO_H = 461;

    final float k;
    final Fonts fonts;
    final Overlay overlay;

    final List<Apps.App> visible = new ArrayList<>();
    final Map<Apps.App, Tile> tiles = new HashMap<>();
    int focus;
    boolean moveMode, showHidden;

    final Spring scroll = new Spring(0.42f, 1f, 0);
    final Spring shelf = new Spring(0.38f, 1f, 1);
    final Spring tiltX = new Spring(0.5f, 0.55f, 0), tiltY = new Spring(0.5f, 0.55f, 0);
    final Spring bumpX = new Spring(0.32f, 0.42f, 0), bumpY = new Spring(0.32f, 0.42f, 0);
    final Spring ambient = new Spring(0.5f, 1f, 1);
    Bitmap ambientFrom, ambientTo;
    float time;

    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final RectF r = new RectF();
    private final Rect full = new Rect();
    private long lastFrame;
    private boolean ticking;

    static final class Tile {
        final Spring x, y, lift = new Spring(0.3f, 1f, 0);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Tile(float x0, float y0) { x = new Spring(0.4f, 0.9f, x0); y = new Spring(0.4f, 0.9f, y0); }
    }

    HomeView(Context c, float k, Fonts fonts) {
        super(c);
        this.k = k;
        this.fonts = fonts;
        this.overlay = new Overlay(this);
        edge.setStyle(Paint.Style.STROKE);
        label.setTypeface(fonts.medium);
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(0xFFFFFFFF);
        label.setTextSize(25 * k);
        label.setShadowLayer(8 * k, 0, 2 * k, 0x99000000);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    // ---- model ---------------------------------------------------------------

    void setApps(List<Apps.App> all, String keepKey) {
        String key = keepKey != null ? keepKey : (focused() != null ? focused().key : null);
        visible.clear();
        for (Apps.App a : all) if (!a.hidden || showHidden) visible.add(a);
        Map<Apps.App, Tile> old = new HashMap<>(tiles);
        tiles.clear();
        for (int i = 0; i < visible.size(); i++) {
            Apps.App a = visible.get(i);
            Tile t = null;
            for (Map.Entry<Apps.App, Tile> e : old.entrySet()) if (e.getKey().key.equals(a.key)) t = e.getValue();
            if (t == null) t = new Tile(slotX(i), slotY(i));
            if (a.tile != null) t.paint.setShader(new BitmapShader(a.tile, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            tiles.put(a, t);
        }
        focus = 0;
        for (int i = 0; i < visible.size(); i++) if (visible.get(i).key.equals(key)) focus = i;
        focus = Math.min(focus, Math.max(0, visible.size() - 1));
        retarget(false);
        Apps.App f = focused();
        if (f != null && ambientTo == null) { ambientTo = f.ambient; ambient.snap(1); }
        overlay.heroNow(f);
        kick();
    }

    Apps.App focused() { return focus < visible.size() ? visible.get(focus) : null; }

    int row(int i) { return i / COLS; }

    float slotX(int i) { return MARGIN + (i % COLS) * (TILE_W + GAP); }

    float slotY(int i) { return TOP_ROW_Y + row(i) * ROW_PITCH; }

    /** Moves focus (or the app being moved) by one step. Returns false at an edge. */
    boolean step(int dx, int dy) {
        int n = visible.size();
        if (n == 0) return false;
        int col = focus % COLS, rw = row(focus), lastRow = row(n - 1);
        int target;
        if (dx != 0) {
            int c = col + dx;
            if (c < 0 || c >= COLS) return bump(dx, 0);
            target = focus + dx;
            if (target >= n) return bump(dx, 0);
        } else {
            int rr = rw + dy;
            if (rr < 0 || rr > lastRow) return bump(0, dy);
            target = Math.min(rr * COLS + col, n - 1);
        }
        if (moveMode) {
            Apps.App a = visible.remove(focus);
            visible.add(target, a);
        } else {
            tiltY.kick(dx * 70);
            tiltX.kick(-dy * 70);
        }
        focus = target;
        retarget(true);
        return true;
    }

    private boolean bump(int dx, int dy) {
        bumpX.kick(dx * 520);
        bumpY.kick(dy * 520);
        kick();
        return false;
    }

    void retarget(boolean animate) {
        for (int i = 0; i < visible.size(); i++) {
            Tile t = tiles.get(visible.get(i));
            if (t == null) continue;
            t.x.to(slotX(i));
            t.y.to(slotY(i));
            t.lift.to(i == focus ? 1 : 0);
            if (!animate) { t.x.snap(slotX(i)); t.y.snap(slotY(i)); }
        }
        int rw = row(focus);
        scroll.to(rw == 0 ? 0 : TOP_ROW_Y + rw * ROW_PITCH - LOWER_ROW_Y);
        shelf.to(rw == 0 ? 1 : 0);
        Apps.App f = focused();
        if (f != null && f.ambient != ambientTo) {
            ambientFrom = ambientTo;
            ambientTo = f.ambient;
            ambient.snap(0);
            ambient.to(1);
        }
        if (rw == 0) overlay.hero(f);
        if (!animate) { scroll.snap(scroll.target); shelf.snap(shelf.target); }
        kick();
    }

    void scrollToTop() {
        if (visible.isEmpty()) return;
        focus = Math.min(focus % COLS, visible.size() - 1);
        retarget(true);
    }

    /** The focused tile's on-screen rectangle, for the launch zoom. */
    Rect focusedRect() {
        Tile t = tiles.get(focused());
        Rect out = new Rect();
        if (t == null) return out;
        out.set(Math.round(t.x.value * k), Math.round((t.y.value - scroll.value) * k),
                Math.round((t.x.value + TILE_W) * k), Math.round((t.y.value + TILE_H - scroll.value) * k));
        return out;
    }

    // ---- animation -------------------------------------------------------------

    void kick() {
        if (!ticking) {
            ticking = true;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long now) {
        float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        time += dt;
        boolean busy = moveMode | overlay.step(dt);
        for (Spring s : new Spring[]{scroll, shelf, tiltX, tiltY, bumpX, bumpY, ambient}) busy |= s.step(dt);
        for (Tile t : tiles.values()) busy |= t.x.step(dt) | t.y.step(dt) | t.lift.step(dt);
        invalidate();
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else ticking = false;
    }

    // ---- drawing ---------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF0A0A0C);
        drawAmbient(c);
        overlay.drawShelf(c);
        c.save();
        c.scale(k, k);
        Apps.App f = focused();
        for (int i = 0; i < visible.size(); i++) if (i != focus) drawTile(c, visible.get(i), false);
        if (f != null) drawTile(c, f, true); // focused tile last, on top
        c.restore();
        overlay.drawChrome(c);
    }

    private void drawAmbient(Canvas c) {
        full.set(0, 0, getWidth(), getHeight());
        float a = ambient.value;
        if (ambientFrom != null && a < 1) {
            image.setAlpha(255);
            c.drawBitmap(ambientFrom, null, full, image);
        }
        if (ambientTo != null) {
            image.setAlpha(Math.round(255 * Math.max(0, Math.min(1, a))));
            c.drawBitmap(ambientTo, null, full, image);
            image.setAlpha(255);
        }
        // Darken so artwork reads as light through frosted glass, more so over the grid and
        // for bright artwork (a white banner must not turn the whole screen grey).
        float luma = focused() != null ? focused().luma : 0;
        int dim = Math.round(255 * Math.min(0.9f, 0.5f + 0.4f * luma + 0.18f * (1 - shelf.value)));
        c.drawColor(dim << 24);
    }

    private void drawTile(Canvas c, Apps.App a, boolean isFocus) {
        Tile t = tiles.get(a);
        if (t == null) return;
        float y = t.y.value - scroll.value;
        if (y > H + 40 || y + TILE_H < -120) return;
        float lift = t.lift.value;
        float scale = 1 + 0.13f * lift + (moveMode && isFocus ? 0.03f : 0);
        float cx = t.x.value + TILE_W / 2, cy = y + TILE_H / 2;
        int save = c.save();
        if (isFocus) c.translate(bumpX.value * 0.06f, bumpY.value * 0.06f);
        if (isFocus && !moveMode) {
            camera.save();
            camera.setLocation(0, 0, -18);
            camera.rotateX(tiltX.value * 0.07f);
            camera.rotateY(tiltY.value * 0.07f);
            camera.getMatrix(matrix);
            camera.restore();
            matrix.preTranslate(-cx * k, -cy * k);
            matrix.postTranslate(cx * k, cy * k);
            c.scale(1 / k, 1 / k);
            c.concat(matrix);
            c.scale(k, k);
        }
        if (moveMode && isFocus) c.rotate((float) Math.sin(time * 2 * Math.PI * 3.1) * 1.4f, cx, cy);
        c.scale(scale, scale, cx, cy);
        c.translate(t.x.value, y);
        r.set(0, 0, TILE_W, TILE_H);

        float alpha = a.hidden ? 0.35f : 1f;
        shadow.setColor(0xFF000000);
        shadow.setAlpha(Math.round(255 * alpha * (0.45f + 0.25f * lift)));
        shadow.setShadowLayer(10 + 34 * lift, 0, 6 + 22 * lift, ((int) (110 + 70 * lift)) << 24);
        c.drawRoundRect(r, CORNER, CORNER, shadow);

        Paint p = t.paint;
        p.setAlpha(Math.round(255 * alpha));
        if (a.tile != null) {
            matrix.reset();
            matrix.setScale(TILE_W / a.tile.getWidth(), TILE_H / a.tile.getHeight());
            p.getShader().setLocalMatrix(matrix);
            c.drawRoundRect(r, CORNER, CORNER, p);
        }
        if (lift > 0.01f) {
            float sx = TILE_W * (0.5f + tiltY.value / 900f);
            int peak = Math.round(56 * lift);
            shine.setShader(new LinearGradient(sx - TILE_W * 0.9f, -TILE_H * 0.4f, sx + TILE_W * 0.4f, TILE_H,
                    new int[]{0, peak << 24 | 0xFFFFFF, 0}, new float[]{0.2f, 0.5f, 0.8f}, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, CORNER, CORNER, shine);
            edge.setStrokeWidth(1.5f);
            edge.setShader(new LinearGradient(0, 0, 0, TILE_H, Math.round(90 * lift) << 24 | 0xFFFFFF, 0,
                    Shader.TileMode.CLAMP));
            r.inset(0.75f, 0.75f);
            c.drawRoundRect(r, CORNER, CORNER, edge);
        }
        c.restoreToCount(save);

        if (isFocus && lift > 0.05f && !moveMode) {
            label.setAlpha(Math.round(255 * Math.min(1, lift * 1.4f)));
            c.drawText(a.label, cx, y + TILE_H * scale / 2 + TILE_H / 2 + 44 + 6 * lift, label);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(this);
        ticking = false;
    }
}
