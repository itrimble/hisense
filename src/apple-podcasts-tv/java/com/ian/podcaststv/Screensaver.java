package com.ian.podcaststv;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.function.BooleanSupplier;

/**
 * Brings up the Now Playing screen once audio has played for a few minutes with the
 * remote put down. Any key puts the page back (that key does nothing else).
 */
final class Screensaver {
    private static final long IDLE_MS = 3 * 60_000;
    private static final long CHECK_MS = 10_000;

    private final NowPlayingScreen screen;
    private final BooleanSupplier ready;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastKey = SystemClock.uptimeMillis();

    private final Runnable check = new Runnable() {
        @Override public void run() {
            ui.postDelayed(this, CHECK_MS);
            if (!screen.isShowing() && SystemClock.uptimeMillis() - lastKey >= IDLE_MS && ready.getAsBoolean()) {
                screen.show();
            }
        }
    };

    /** `ready`: audio is playing and nothing else wants the screen (video, a menu). */
    Screensaver(NowPlayingScreen screen, BooleanSupplier ready) {
        this.screen = screen;
        this.ready = ready;
        ui.postDelayed(check, CHECK_MS);
    }

    /** Every remote key. Returns true when the key only woke the page. */
    boolean key() {
        lastKey = SystemClock.uptimeMillis();
        if (!screen.isShowing()) return false;
        screen.hide();
        return true;
    }

    void stop() { ui.removeCallbacks(check); screen.hide(); }
}
