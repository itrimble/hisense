package com.ian.podcaststv;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Sleep timer with Apple Podcasts' choices: a number of minutes, or the end of the
 * current episode. When time is up it asks the owner to fade out and pause.
 */
final class SleepTimer {
    static final String[] LABELS = {"Off", "5 minutes", "10 minutes", "15 minutes", "30 minutes",
            "45 minutes", "1 hour", "When current episode ends"};
    static final int[] MINUTES = {0, 5, 10, 15, 30, 45, 60, -1}; // -1 = end of episode

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable onFire;
    private long endsAt;      // uptime ms; 0 = no countdown
    private boolean atEnd;

    private final Runnable fire = new Runnable() {
        @Override public void run() {
            endsAt = 0;
            onFire.run();
        }
    };

    SleepTimer(Runnable onFire) { this.onFire = onFire; }

    /** minutes > 0 counts down, -1 waits for the episode to end, 0 turns the timer off. */
    void set(int minutes) {
        handler.removeCallbacks(fire);
        endsAt = 0;
        atEnd = minutes < 0;
        if (minutes > 0) {
            endsAt = SystemClock.uptimeMillis() + minutes * 60_000L;
            handler.postAtTime(fire, endsAt);
        }
    }

    boolean atEpisodeEnd() { return atEnd; }

    /** The episode ended while waiting for it: the timer is used up. */
    void episodeEnded() { atEnd = false; }

    boolean active() { return atEnd || endsAt > 0; }

    /** Short status for menus and the player, e.g. "23 min left". */
    String status() {
        if (atEnd) return "end of episode";
        if (endsAt == 0) return "off";
        long left = Math.max(0, endsAt - SystemClock.uptimeMillis());
        long min = (left + 59_999) / 60_000;
        return min + " min left";
    }

    int choiceIndex() {
        if (atEnd) return MINUTES.length - 1;
        return endsAt == 0 ? 0 : -1;
    }
}
