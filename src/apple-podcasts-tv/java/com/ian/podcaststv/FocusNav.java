package com.ian.podcaststv;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.function.Supplier;

/**
 * Arrow-key navigation: the remote moves a highlight between the page's controls
 * (assets/nav.js) and select taps the highlighted one. Inside Apple's cross-origin
 * login frame, which the page script cannot see into, arrows become Tab / Shift+Tab
 * so they step between its boxes (including the six 2FA digits).
 */
final class FocusNav {
    private final WebView web;
    private final CursorView cursor;
    private final Supplier<View> target;
    private static final long HOVER_DELAY_MS = 600; // each hover redraw costs ~250 ms here: only once the viewer rests
    private boolean pending, inFrame, leaving;
    private float hoverX, hoverY;
    private long keyAt;
    private final Runnable hover;

    private AuthBridge auth;

    /** Inside Apple's sign-in frame the helper there moves a ring between its boxes. */
    void useAuth(AuthBridge a) {
        auth = a;
        a.onReply(o -> {
            if (o.has("edge")) {           // stepped out of the frame: carry on outside it
                inFrame = false;
                leaving = true;
                String d = o.optString("edge");
                move(d.equals("left") ? -1 : d.equals("right") ? 1 : 0, d.equals("up") ? -1 : d.equals("down") ? 1 : 0);
            } else if (o.optBoolean("field")) {
                showKeyboard();
            }
        });
    }

    FocusNav(WebView web, CursorView cursor, Supplier<View> target) {
        this.web = web;
        this.cursor = cursor;
        this.target = target;
        this.hover = () -> cursor.hoverAt(hoverX, hoverY);
    }

    void move(int dx, int dy) {
        if (target.get() != web) { arrowKey(target.get(), dx, dy); return; } // popups: plain keys
        if (inFrame) {
            if (auth != null && auth.connected()) {
                auth.send("nav", null, dx < 0 ? "left" : dx > 0 ? "right" : dy < 0 ? "up" : "down");
            } else {
                tab(dx < 0 || dy < 0);
            }
            return;
        }
        if (pending) return; // drop key repeats while a step is still being worked out
        pending = true;
        keyAt = SystemClock.uptimeMillis();
        boolean out = leaving;
        leaving = false;
        web.evaluateJavascript("window.__ptv && __ptv.nav ? __ptv.nav.move(" + dx + "," + dy + "," + out + ") : null", r -> {
            pending = false;
            JSONObject o = parse(r);
            if (o == null) return;
            if (o.optBoolean("edge") && dy != 0) cursor.scroll(dy < 0 ? 6 : -6); // nothing further: scroll the page
            if (!o.optBoolean("ok")) return;
            inFrame = o.optBoolean("frame");
            if (inFrame && auth != null && auth.connected()) auth.send("navEnter", null, null);
        });
    }

    /**
     * nav.js reports where the highlight settled (CSS px). Once the viewer rests there
     * for a moment, hover it: that reveals Apple's on-card play buttons. Hovering on
     * every step made Apple's page restyle cards as the highlight passed over them.
     */
    void focusedAt(double x, double y, double vw) {
        if (keyAt > 0) {
            Log.d(MainActivity.TAG, "nav step " + (SystemClock.uptimeMillis() - keyAt) + " ms");
            keyAt = 0;
        }
        float k = vw > 0 ? (float) (web.getWidth() * web.getScaleX() / vw) : 1f; // to screen pixels
        hoverX = (float) x * k;
        hoverY = (float) y * k;
        web.removeCallbacks(hover);
        web.postDelayed(hover, HOVER_DELAY_MS);
    }

    /** Select: press the highlighted control (nav.js clicks it, or focuses a text box). */
    void select() {
        if (inFrame && auth != null && auth.connected()) { auth.send("navSelect", null, null); return; }
        web.evaluateJavascript("window.__ptv && __ptv.nav ? __ptv.nav.select() : null", r -> {
            JSONObject o = parse(r);
            if (o == null || o.optBoolean("none")) { move(0, 1); return; }
            pressed(o.optString("did"));
        });
    }

    /** Pointer mode: press whatever is under the pointer at view pixel (x, y). */
    void clickAt(float x, float y) {
        web.evaluateJavascript("window.__ptv && __ptv.nav ? __ptv.nav.clickAt(" + x + " / (" + web.getWidth()
                + " / innerWidth), " + y + " / (" + web.getWidth() + " / innerWidth)) : null", r -> {
            JSONObject o = parse(r);
            if (o != null) pressed(o.optString("did"));
        });
    }

    private void pressed(String did) {
        if (did.equals("frame")) {
            inFrame = true; // Apple's form now takes the arrows
            if (auth != null && auth.connected()) auth.send("navEnter", null, null);
        }
        if (did.equals("field")) showKeyboard();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) web.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(web, 0);
    }

    /** Back inside the login frame steps out of it first. */
    boolean leaveFrame() {
        if (!inFrame) return false;
        inFrame = false;
        if (auth != null) auth.send("navLeave", null, null);
        return true;
    }

    void reset() {
        if (inFrame && auth != null) auth.send("navLeave", null, null);
        inFrame = false;
        pending = false;
        web.evaluateJavascript("window.__ptv && __ptv.nav && __ptv.nav.clear()", null);
    }

    private void tab(boolean back) {
        long now = SystemClock.uptimeMillis();
        int meta = back ? KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON : 0;
        web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, meta));
        web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, meta));
    }

    private static void arrowKey(View v, int dx, int dy) {
        int code = dx < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT
                : dy < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
        long now = SystemClock.uptimeMillis();
        v.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        v.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    /** evaluateJavascript hands back a JSON-encoded string holding our JSON. */
    private static JSONObject parse(String r) {
        try {
            Object v = new JSONTokener(r).nextValue();
            return v instanceof String ? new JSONObject((String) v) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
