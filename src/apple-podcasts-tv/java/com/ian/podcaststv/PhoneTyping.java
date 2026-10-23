package com.ian.podcaststv;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Types text sent from the phone into the page. Each action first makes sure a
 * text box has focus (the viewer's choice, else the sign-in box); evaluateJavascript
 * callbacks run in order, so "type" then "enter" stay in sequence.
 */
final class PhoneTyping implements PhoneKeyboard.Sink {
    /** Gap between phone actions; Chromium drops native keys that land on top of each other. */
    private static final long KEY_GAP_MS = 150;

    private final WebView web;
    private final Supplier<View> target;
    private final AuthBridge auth;
    private long nextKeyAt;

    PhoneTyping(WebView web, Supplier<View> target, AuthBridge auth) {
        this.web = web;
        this.target = target;
        this.auth = auth;
    }

    @Override
    public void type(String text) {
        // Same-origin boxes (Apple's email step) get a real browser edit; boxes in Apple's
        // cross-origin sign-in frame (password, two-factor code) go to its helper; native
        // key events are the last resort.
        inPage("__ptv.typeText(" + JSONObject.quote(text) + ")", () -> auth.send("text", text), t -> {
            hideTvKeyboard(t); // native keys sent while the TV keyboard is up get lost
            KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
            if (events != null) {
                for (KeyEvent e : events) t.dispatchKeyEvent(e);
                return;
            }
            // Characters with no key mapping (emoji, accents): commit through the IME path.
            InputConnection ic = t.onCreateInputConnection(new EditorInfo());
            if (ic != null) ic.commitText(text, 1);
        });
    }

    @Override
    public void key(String name) {
        if (name.equals("backspace")) {
            inPage("__ptv.deleteBack()", () -> auth.send("backspace", null), t -> pressKey(t, KeyEvent.KEYCODE_DEL));
            return;
        }
        // Enter and Tab must be real keys so the page submits / moves focus. Enter also
        // marks the box as finished, so the next text goes to the next step's box.
        boolean enter = name.equals("enter");
        int code = enter ? KeyEvent.KEYCODE_ENTER : KeyEvent.KEYCODE_TAB;
        inPage(enter ? "__ptv.beforeEnter()" : "(__ptv.focusField() === 'frame' ? 'frame' : false)",
                () -> auth.send(name, null), t -> pressKey(t, code));
    }

    private static void pressKey(View t, int code) {
        long now = SystemClock.uptimeMillis();
        t.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        t.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private static void hideTvKeyboard(View t) {
        InputMethodManager imm = (InputMethodManager) t.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(t.getWindowToken(), 0);
    }

    /**
     * Runs {@code script} in the page: it returns true when it handled the action there,
     * "frame" when the box is in Apple's sign-in frame (then {@code inFrame} hands it to the
     * helper in that frame), else false ({@code nativeKeys} on the target view). Actions
     * are spaced and stay in order: evaluateJavascript callbacks run in submission order.
     */
    private void inPage(String script, Runnable inFrame, Consumer<View> nativeKeys) {
        long now = SystemClock.uptimeMillis();
        long at = Math.max(now, nextKeyAt);
        nextKeyAt = at + KEY_GAP_MS;
        web.postDelayed(() -> {
            View t = target.get();
            t.requestFocus();
            if (t != web) { nativeKeys.accept(t); return; }
            web.evaluateJavascript("window.__ptv ? " + script + " : false", handled -> {
                if ("true".equals(handled)) {
                    Log.i(MainActivity.TAG, "phone keyboard: page edit");
                } else if ("\"frame\"".equals(handled) && auth.connected()) {
                    Log.i(MainActivity.TAG, "phone keyboard: sign-in frame");
                    inFrame.run();
                } else {
                    Log.i(MainActivity.TAG, "phone keyboard: native keys");
                    nativeKeys.accept(t);
                }
            });
        }, at - now);
    }
}
