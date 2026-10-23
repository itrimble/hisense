package com.ian.podcaststv;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.webkit.WebView;

import java.util.function.BooleanSupplier;

/**
 * WebView whose on-screen-keyboard action key (→|, Go, Done, Search) presses
 * Enter. Chromium maps the action to "move to the next field", which does
 * nothing on Apple's one-field-at-a-time sign-in form.
 *
 * It also stays "visible" to the page while an episode is loaded and the app is in the
 * background. Chromium suspends a paused player on a hidden page and stops its timers, so
 * the remote's play key could not resume the episode from another app.
 */
class TvWebView extends WebView {
    BooleanSupplier stayVisible = () -> false;
    private boolean shownToPage = true;

    TvWebView(Context c) {
        super(c);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        int v = visibility != View.VISIBLE && stayVisible.getAsBoolean() ? View.VISIBLE : visibility;
        shownToPage = v == View.VISIBLE;
        super.onWindowVisibilityChanged(v);
    }

    /** stayVisible may have changed while the window is hidden: apply it now. */
    void visibilityRuleChanged() {
        boolean want = getWindowVisibility() == View.VISIBLE || stayVisible.getAsBoolean();
        if (want != shownToPage) onWindowVisibilityChanged(getWindowVisibility());
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection ic = super.onCreateInputConnection(outAttrs);
        if (ic == null) return null;
        // Show "Go" instead of "Next" so the key's label matches what it does.
        outAttrs.imeOptions = (outAttrs.imeOptions & ~EditorInfo.IME_MASK_ACTION) | EditorInfo.IME_ACTION_GO;
        return new InputConnectionWrapper(ic, true) {
            @Override
            public boolean performEditorAction(int action) {
                if (action == EditorInfo.IME_ACTION_PREVIOUS) return super.performEditorAction(action);
                pressEnter();
                return true;
            }
        };
    }

    void pressEnter() {
        long now = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;
        dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags));
        dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags));
    }
}
