package com.ian.podcaststv;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The episode that is playing, from Apple's web player (nowplaying.js) or the native
 * video player. The media session, screensaver, launcher card and chapters read it.
 * The last episode is remembered so the next launch can offer to continue it.
 */
final class NowPlaying {
    private static final String LAST = "last_episode";

    String id = "", title = "", show = "", art = "", url = "";
    double position, duration;
    float rate = 1f;
    boolean playing, video;
    /** Wall-clock ms when {@link #position} was read. */
    long savedAt = System.currentTimeMillis();

    static NowPlaying fromJson(JSONObject o) {
        NowPlaying n = new NowPlaying();
        n.id = o.optString("id");
        n.title = o.optString("title");
        n.show = o.optString("show");
        n.art = o.optString("art");
        n.url = o.optString("url");
        n.position = o.optDouble("position", 0);
        n.duration = o.optDouble("duration", 0);
        n.playing = o.optBoolean("playing");
        n.video = o.optBoolean("video");
        n.rate = (float) o.optDouble("rate", 1);
        return n;
    }

    /** Sets the position as of now. */
    void at(double sec) {
        position = sec;
        savedAt = System.currentTimeMillis();
    }

    /** Where playback is now: the last report plus the time since, at the playing speed. */
    double now() {
        double p = position;
        if (playing) p += (System.currentTimeMillis() - savedAt) / 1000.0 * rate;
        return duration > 0 ? Math.min(p, duration) : p;
    }

    /** Remembers this episode and position for the "continue" card. */
    void remember(SharedPreferences prefs) {
        if (id.isEmpty() || url.isEmpty()) return;
        try {
            JSONObject o = new JSONObject().put("id", id).put("title", title).put("show", show).put("art", art)
                    .put("url", url).put("position", now()).put("duration", duration).put("video", video)
                    .put("when", System.currentTimeMillis());
            prefs.edit().putString(LAST, o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** The last episode played and when (key "when"), or null. */
    static JSONObject last(SharedPreferences prefs) {
        String s = prefs.getString(LAST, null);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    /** The episode was finished: nothing to continue. */
    static void forget(SharedPreferences prefs) {
        prefs.edit().remove(LAST).apply();
    }
}
