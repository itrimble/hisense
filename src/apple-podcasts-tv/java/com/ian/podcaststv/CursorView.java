package com.ian.podcaststv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

/**
 * tvOS-style pointer drawn over the WebView. The D-pad moves it; it turns
 * moves into mouse hovers, OK into a tap, and edge pushes into wheel scrolls
 * on whichever view {@link Target} returns. Fades out when idle.
 */
class CursorView extends View {
    interface Target { View get(); }

    private static final long IDLE_MS = 5000;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius, step;
    private final Target target;
    private float x = -1, y = -1;

    private final Runnable fade = () -> animate().alpha(0f).setDuration(400).withEndAction(() -> setVisibility(INVISIBLE)).start();

    CursorView(Context c, Target target) {
        super(c);
        this.target = target;
        float d = getResources().getDisplayMetrics().density;
        radius = 11 * d;
        step = 9 * d;
        fill.setColor(Color.argb(215, 255, 255, 255));
        fill.setShadowLayer(6 * d, 0, 2 * d, Color.argb(140, 0, 0, 0));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * d);
        ring.setColor(Color.argb(150, 40, 40, 40));
        // No software layer: on this TV a full-screen software layer cost ~1 s per frame
        // (re-rendering and uploading a 1920x1080 bitmap). Shadows draw on the GPU here (API 28+).
        setFocusable(false);
        setVisibility(INVISIBLE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (x < 0) moveTo(w / 2f, h / 2f);
    }

    void moveTo(float nx, float ny) {
        x = nx;
        y = ny;
        removeCallbacks(fade);
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(1f);
        postDelayed(fade, IDLE_MS);
        invalidate();
    }

    void hideNow() {
        removeCallbacks(fade);
        animate().cancel();
        setAlpha(0f);
        setVisibility(INVISIBLE); // out of the frame entirely, not just transparent
    }

    /** Moves by one D-pad press; held keys accelerate. Pushing past top/bottom scrolls. */
    void nudge(int dx, int dy, int repeat) {
        if (x < 0) return;
        float speed = step * Math.min(1f + repeat * 0.4f, 7f);
        float w = getWidth() - 1, h = getHeight() - 1;
        float nx = x + dx * speed, ny = y + dy * speed;
        if (ny < 0) { scroll(1 + repeat / 4); ny = 0; }
        if (ny > h) { scroll(-1 - repeat / 4); ny = h; }
        moveTo(Math.max(0, Math.min(w, nx)), ny);
        View t = target.get();
        MotionEvent hover = mouseEvent(SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE, lx(t, x), ly(t, y), 0);
        t.dispatchGenericMotionEvent(hover);
        hover.recycle();
    }

    /** Wheel-scrolls whatever is under the pointer's column; positive = up. */
    void scroll(float amount) {
        if (x < 0) return;
        moveTo(x, y);
        float sy = Math.max(getHeight() * 0.25f, Math.min(getHeight() * 0.75f, y));
        View t = target.get();
        MotionEvent e = mouseEvent(SystemClock.uptimeMillis(), MotionEvent.ACTION_SCROLL, lx(t, x), ly(t, sy), amount);
        t.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    /** Arrow mode: hover over a point without showing the pointer. */
    void hoverAt(float nx, float ny) {
        x = nx;
        y = ny;
        hideNow();
        View t = target.get();
        MotionEvent hover = mouseEvent(SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE, lx(t, x), ly(t, y), 0);
        t.dispatchGenericMotionEvent(hover);
        hover.recycle();
    }

    /** Taps the page under the pointer. */
    void click() {
        if (x < 0) return;
        moveTo(x, y);
        tap();
    }

    private void tap() {
        View t = target.get();
        t.requestFocus();
        final float cx = lx(t, x), cy = ly(t, y);
        final long downAt = SystemClock.uptimeMillis();
        MotionEvent down = touchEvent(downAt, downAt, MotionEvent.ACTION_DOWN, cx, cy);
        t.dispatchTouchEvent(down);
        down.recycle();
        t.postDelayed(() -> {
            MotionEvent up = touchEvent(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, cx, cy);
            t.dispatchTouchEvent(up);
            up.recycle();
        }, 40);
    }

    /** Screen position -> the target view's own pixels (it may be drawn scaled up from the top-left). */
    private static float lx(View t, float v) { return (v - t.getLeft()) / t.getScaleX(); }

    private static float ly(View t, float v) { return (v - t.getTop()) / t.getScaleY(); }

    float pointerX() { return x; }

    float pointerY() { return y; }

    /**
     * A touchscreen event shaped exactly like the system's own "input tap" (no tool
     * type): Apple's page cancels the click for touches that report a finger.
     */
    private static MotionEvent touchEvent(long downAt, long time, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(downAt, time, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return e;
    }

    private static MotionEvent mouseEvent(long time, int action, float x, float y, float vscroll) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = 1f;
        pc.size = 1f;
        if (vscroll != 0) pc.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        return MotionEvent.obtain(time, time, action, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (x < 0) return;
        canvas.drawCircle(x, y, radius, fill);
        canvas.drawCircle(x, y, radius, ring);
    }
}
