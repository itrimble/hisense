package com.ian.podcaststv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Locale;

/**
 * Full-screen video through Android's own player. The web player hands over the
 * episode's file and position; the TV's hardware decoder draws straight to the
 * video plane, which the web player's GPU path on this set cannot keep up with
 * (1080p dropped ~90% of frames). Closing hands the position back.
 *
 * Remote, as on Apple TV: a click left/right skips back 15 s / ahead 30 s; holding
 * scans at 2x and each further press steps 2x, 3x, 4x, 8x, 16x, 32x (the other
 * direction slows down, then reverses); OK or Play resumes there, Back cancels.
 */
final class NativeVideo {
    interface Listener {
        void onState(boolean playing, String title, String artist);
        void onClosed(double positionSec);
        void onFailed(double positionSec);
        void onEnded();
    }

    private static final int MATCH = FrameLayout.LayoutParams.MATCH_PARENT;
    private static final long OSD_MS = 4000;
    private static final int HOLD_REPEATS = 1;       // first key repeat (~0.4 s held) starts scanning
    private static final int[] LADDER = {-32, -16, -8, -4, -3, -2, 0, 2, 3, 4, 8, 16, 32};
    private static final long SCAN_TICK_MS = 250;

    private final Activity activity;
    private final FrameLayout root;
    private final Listener listener;
    private final float d;
    private FrameLayout layer;
    private VideoView video;
    private View osd;
    private TextView status, clock;
    private ProgressBar bar;
    private String title = "", artist = "";
    private int startMs;
    private boolean prepared;
    private MediaPlayer player;
    private float speed = 1f;
    private int scanRate;          // 0 = playing normally; else x real time, negative = rewind
    private double scanPos, scanFrom;
    private boolean seekBusy, pressUsed;
    private java.util.function.Supplier<String> status2 = () -> null;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (video == null) return;
            updateOsd();
            video.postDelayed(this, 500);
        }
    };
    private final Runnable scanTick = new Runnable() {
        @Override public void run() {
            if (video == null || scanRate == 0) return;
            int dur = video.getDuration();
            scanPos += scanRate * SCAN_TICK_MS;
            if (scanPos <= 0 || (dur > 0 && scanPos >= dur - 1000)) {
                scanPos = Math.max(0, Math.min(scanPos, dur - 1000));
                endScan(true);
                return;
            }
            if (!seekBusy) { seekBusy = true; video.seekTo((int) scanPos); }
            updateOsd();
            video.postDelayed(this, SCAN_TICK_MS);
        }
    };
    private final Runnable hideOsd = () -> { if (video != null && video.isPlaying() && osd != null) osd.animate().alpha(0f).setDuration(300); };

    NativeVideo(Activity activity, FrameLayout root, Listener listener) {
        this.activity = activity;
        this.root = root;
        this.listener = listener;
        this.d = activity.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() { return layer != null; }

    void play(String url, double startSec, String title, String artist, float speed) {
        close(false);
        this.speed = speed;
        scanRate = 0;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        startMs = (int) Math.max(0, startSec * 1000);
        prepared = false;

        layer = new FrameLayout(activity);
        layer.setBackgroundColor(Color.BLACK);
        video = new VideoView(activity);
        video.setKeepScreenOn(true);
        layer.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        status = text(22, true);
        status.setText("Loading…");
        layer.addView(status, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        osd = buildOsd();
        layer.addView(osd, new FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root.addView(layer, root.getChildCount() - 1, new FrameLayout.LayoutParams(MATCH, MATCH)); // under the pointer

        video.setOnPreparedListener(mp -> {
            prepared = true;
            player = mp;
            mp.setOnSeekCompleteListener(p -> seekBusy = false);
            status.setVisibility(View.GONE);
            if (startMs > 0) video.seekTo(startMs);
            resume();
            changed();
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) { status.setText("Loading…"); status.setVisibility(View.VISIBLE); }
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) status.setVisibility(View.GONE);
                return false;
            });
        });
        video.setOnCompletionListener(mp -> { changed(); showOsd(); listener.onEnded(); });
        video.setOnErrorListener((mp, what, extra) -> {
            Log.w(MainActivity.TAG, "native video failed " + what + "/" + extra);
            double at = position();
            close(false);
            listener.onFailed(at);
            return true;
        });
        video.setVideoURI(Uri.parse(url));
        video.requestFocus();
        showOsd();
        video.post(tick);
    }

    /** Remote and media-session commands while the native player is up. */
    void command(String cmd) {
        if (video == null) return;
        if (scanRate != 0 && (cmd.equals("toggle") || cmd.equals("play") || cmd.equals("pause"))) {
            endScan(true);
            if (cmd.equals("pause")) video.pause();
        } else {
            switch (cmd) {
                case "toggle": if (video.isPlaying()) video.pause(); else resume(); break;
                case "play": resume(); break;
                case "pause": video.pause(); break;
                case "fwd": case "next": seekBy(30_000); break;
                case "back": case "prev": seekBy(-15_000); break;
                case "scanfwd": scanStep(1); break;
                case "scanback": scanStep(-1); break;
                default: return;
            }
        }
        changed();
        showOsd();
    }

    /** Left/right while the player is up (down and up events). */
    boolean arrowKey(KeyEvent e) {
        int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                pressUsed = scanRate != 0;
                if (pressUsed) scanStep(dir);          // already scanning: change speed
            } else if (e.getRepeatCount() == HOLD_REPEATS && scanRate == 0) {
                pressUsed = true;
                scanStep(dir);                         // held: start scanning at 2x
            }
            showOsd();
            return true;
        }
        if (!pressUsed) command(dir > 0 ? "fwd" : "back"); // a click skips
        pressUsed = false;
        return true;
    }

    /** Back while scanning returns to where the scan began. */
    boolean cancelScan() {
        if (scanRate == 0) return false;
        endScan(false);
        return true;
    }

    void setSpeed(float s) {
        speed = s;
        if (video != null && video.isPlaying()) applySpeed();
        showOsd();
    }

    float speed() { return speed; }

    /** Sleep timer: fade the sound out over a few seconds, then pause. */
    void fadeOutAndPause(long ms) {
        if (video == null || player == null || !video.isPlaying()) return;
        final long start = android.os.SystemClock.uptimeMillis();
        video.post(new Runnable() {
            @Override public void run() {
                if (video == null || player == null) return;
                float k = 1f - (android.os.SystemClock.uptimeMillis() - start) / (float) ms;
                if (k > 0) {
                    player.setVolume(k, k);
                    video.postDelayed(this, 100);
                    return;
                }
                video.pause();
                player.setVolume(1f, 1f);
                changed();
                showOsd();
            }
        });
    }

    /** Extra status for the time bar (the sleep timer); null for none. */
    void setStatus(java.util.function.Supplier<String> status) { this.status2 = status; }

    String show() { return artist; }

    private void resume() {
        video.start();
        applySpeed();
    }

    private void applySpeed() {
        if (player == null) return;
        try {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
        } catch (RuntimeException e) {
            Log.w(MainActivity.TAG, "speed " + speed + " not supported", e);
        }
    }

    private void scanStep(int dir) {
        if (!prepared) return;
        int i = 0;
        while (LADDER[i] != scanRate) i++;
        int rate = LADDER[Math.max(0, Math.min(LADDER.length - 1, i + dir))];
        if (rate == 0) { endScan(true); return; }
        if (scanRate == 0) {
            scanFrom = scanPos = video.getCurrentPosition();
            video.pause();
            scanRate = rate;
            video.postDelayed(scanTick, SCAN_TICK_MS);
        }
        scanRate = rate;
        changed();
    }

    private void endScan(boolean keep) {
        video.removeCallbacks(scanTick);
        scanRate = 0;
        video.seekTo((int) (keep ? scanPos : scanFrom));
        resume();
        changed();
        showOsd();
    }

    void showOsd() {
        if (osd == null) return;
        osd.animate().cancel();
        osd.setAlpha(1f);
        updateOsd();
        osd.removeCallbacks(hideOsd);
        osd.postDelayed(hideOsd, OSD_MS);
    }

    /** Closes the player and reports where it stopped. */
    void close() { close(true); }

    private void close(boolean report) {
        if (layer == null) return;
        double at = scanRate != 0 ? scanFrom / 1000.0 : position();
        video.removeCallbacks(scanTick);
        video.removeCallbacks(tick);
        osd.removeCallbacks(hideOsd);
        video.stopPlayback();
        root.removeView(layer);
        layer = null;
        video = null;
        osd = null;
        player = null;
        scanRate = 0;
        if (report) listener.onClosed(at);
    }

    /** Where playback is, in seconds (also while paused). */
    double positionSec() { return position(); }

    double durationSec() { return prepared && video != null ? video.getDuration() / 1000.0 : 0; }

    boolean isPlaying() { return video != null && video.isPlaying(); }

    void seekToSec(double t) {
        if (!prepared || video == null) return;
        if (scanRate != 0) endScan(false);
        video.seekTo((int) Math.max(0, t * 1000));
        showOsd();
    }

    private double position() {
        if (video == null) return startMs / 1000.0;
        return prepared ? video.getCurrentPosition() / 1000.0 : startMs / 1000.0;
    }

    private void seekBy(int ms) {
        if (!prepared) return;
        int dur = video.getDuration();
        int to = Math.max(0, video.getCurrentPosition() + ms);
        if (dur > 0) to = Math.min(dur - 1000, to);
        video.seekTo(to);
    }

    private void changed() {
        listener.onState(video != null && video.isPlaying(), title, artist);
    }

    // ---- on-screen display ------------------------------------------------------

    private View buildOsd() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (36 * d);
        box.setPadding(pad, pad * 2, pad, (int) (28 * d));
        box.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000}));
        TextView sub = text(13, false);
        sub.setText(artist);
        sub.setAlpha(0.7f);
        TextView name = text(20, true);
        name.setText(title);
        name.setSingleLine(true);
        box.addView(sub);
        box.addView(name);
        bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (int) (6 * d));
        blp.topMargin = (int) (14 * d);
        box.addView(bar, blp);
        clock = text(13, false);
        clock.setAlpha(0.85f);
        box.addView(clock);
        return box;
    }

    private void updateOsd() {
        if (video == null || bar == null) return;
        int dur = prepared ? video.getDuration() : 0;
        int pos = scanRate != 0 ? (int) scanPos : prepared ? video.getCurrentPosition() : startMs;
        bar.setProgress(dur > 0 ? (int) (1000L * pos / dur) : 0);
        String state;
        if (scanRate > 0) state = "▶▶ " + scanRate + "×  ·  ";
        else if (scanRate < 0) state = "◀◀ " + -scanRate + "×  ·  ";
        else if (!video.isPlaying()) state = prepared ? "Paused  ·  " : "";
        else state = speed != 1f ? Speed.label(speed) + "  ·  " : "";
        String hint = scanRate != 0 ? "      ◀ ▶ change speed   OK play from here   Back cancel"
                : "      OK play/pause   ◀ 15 s   30 s ▶   hold ◀ ▶ to scan   Back to close";
        String extra = status2.get();
        clock.setText(state + fmt(pos) + (dur > 0 ? "  /  " + fmt(dur) : "")
                + (extra != null ? "  ·  " + extra : "") + hint);
        if (!video.isPlaying()) { osd.removeCallbacks(hideOsd); osd.setAlpha(1f); }
    }

    private static String fmt(int ms) {
        int s = ms / 1000, h = s / 3600, m = (s / 60) % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.US, "%d:%02d", m, s % 60);
    }

    private TextView text(int sp, boolean bold) {
        TextView t = new TextView(activity);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setShadowLayer(6 * d, 0, 2 * d, 0xAA000000);
        return t;
    }
}
