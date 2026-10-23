package com.ian.podcaststv;

import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Playback speed, as in Apple Podcasts: 0.5x to 3x, remembered per show (Apple's app
 * keeps a preferred speed for each show you follow), with the last choice as the
 * default for shows that have none.
 */
final class Speed {
    static final float[] PRESETS = {0.5f, 0.75f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.25f, 1.3f, 1.5f, 1.75f, 2f, 2.5f, 3f};

    private final SharedPreferences prefs;

    Speed(SharedPreferences prefs) { this.prefs = prefs; }

    float forShow(String show) {
        float fallback = prefs.getFloat("speed", 1f);
        return show == null || show.isEmpty() ? fallback : prefs.getFloat("speed:" + show, fallback);
    }

    void set(String show, float speed) {
        SharedPreferences.Editor e = prefs.edit().putFloat("speed", speed);
        if (show != null && !show.isEmpty()) e.putFloat("speed:" + show, speed);
        e.apply();
    }

    /** The next preset up, wrapping to the slowest (for a one-button cycle). */
    static float next(float s) {
        for (float p : PRESETS) if (p > s + 0.001f) return p;
        return PRESETS[0];
    }

    static String label(float s) {
        String n = String.format(Locale.US, "%.2f", s).replaceAll("0+$", "").replaceAll("\\.$", "");
        return n + "×";
    }

    static String[] labels() {
        String[] out = new String[PRESETS.length];
        for (int i = 0; i < PRESETS.length; i++) out[i] = label(PRESETS[i]);
        return out;
    }

    static int indexOf(float s) {
        int best = 0;
        for (int i = 0; i < PRESETS.length; i++) {
            if (Math.abs(PRESETS[i] - s) < Math.abs(PRESETS[best] - s)) best = i;
        }
        return best;
    }
}
