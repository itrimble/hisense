package com.ian.podcaststv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Who plays what: audio stays in Apple's web player, video episodes go to the native
 * player. Routes remote and media-session commands to whichever is active, owns playback
 * speed (per show, as in Apple's app), keeps track of what is playing (for the media
 * controls, screensaver and "continue" card), saves the position reached in the native
 * player back to Apple, and offers the episode's chapters.
 */
final class Playback {
    private static final long FADE_MS = 8000;
    private static final long SYNC_MS = 30_000;

    private final Activity activity;
    private WebView web;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean webSuspended;
    private final NativeVideo video;
    private final Speed speeds;
    private final SharedPreferences prefs;
    private final Consumer<Boolean> videoMode;
    private final Runnable beforeVideo;
    private final SleepTimer sleep = new SleepTimer(this::sleepNow);
    private String show = "";
    private float webSpeed = 1f;
    private PrivateFeeds.Episode privateEpisode;
    private MediaPlayer privateAudio;
    private boolean privatePrepared, privateWantsPlay, privateFocus;
    private final AudioManager.OnAudioFocusChangeListener privateFocusListener = change -> {
        if (change < 0 && privateEpisode != null && privateAudio != null) privateCommand("pause");
    };
    private final Runnable privateTick = new Runnable() {
        @Override public void run() {
            if (privateEpisode == null || privateAudio == null) return;
            privateChanged();
            ui.postDelayed(this, 1000);
        }
    };
    private NowPlaying now;          // null until something has played
    private Bitmap art;
    private Runnable onChange = () -> { };
    private String chaptersFor = "";
    private final List<Chapters.Chapter> chapters = new ArrayList<>();

    private final Runnable sync = new Runnable() {
        @Override public void run() {
            if (!video.isShowing()) return;
            videoChanged();
            if (video.isPlaying()) savePosition(false);
            ui.postDelayed(this, SYNC_MS);
        }
    };

    Playback(Activity activity, WebView web, FrameLayout root, Speed speeds, SharedPreferences prefs,
             Consumer<Boolean> videoMode, Runnable beforeVideo) {
        this.activity = activity;
        this.web = web;
        this.speeds = speeds;
        this.prefs = prefs;
        this.videoMode = videoMode;
        this.beforeVideo = beforeVideo;
        this.video = new NativeVideo(activity, root, new VideoEvents());
        video.setStatus(this::videoStatus);
    }

    /** Called whenever what is playing (or its artwork) changes. */
    void setOnChange(Runnable r) { onChange = r; }

    NowPlaying now() { return now; }

    Bitmap art() { return art; }

    boolean audioPlaying() { return now != null && now.playing && !video.isShowing(); }
    boolean audioLoaded() { return now != null && !now.video && !video.isShowing(); }

    boolean privateActive() { return privateEpisode != null; }

    boolean videoShowing() { return video.isShowing(); }

    boolean webNeedsResume() { return webSuspended && privateEpisode == null && now != null; }

    void attachWeb(WebView view) { web = view; }

    /** Called only after five minutes stopped and paused. Keep the media session's
     * metadata, but release the document/decoder; the next Play reloads the episode. */
    void detachIdleWeb() {
        if (now != null) now.remember(prefs);
        if (privateAudio != null && (privatePrepared ? !privateAudio.isPlaying() : !privateWantsPlay)) {
            privateChanged(); privateAudio.release(); privateAudio = null;
            privatePrepared = false; abandonPrivateFocus();
            ui.removeCallbacks(privateTick);
        }
        webSuspended = true;
        web = null;
    }

    /** Private RSS uses native playback and local positions; it has no Apple catalog ID. */
    void playPrivate(PrivateFeeds.Episode episode, double at) {
        stopPrivate();
        if (video.isShowing()) video.close();
        Runnable begin = () -> {
            privateEpisode = episode;
            js("(__ptv.privatePlaying = true)");
            show = episode.show;
            NowPlaying n = new NowPlaying();
            n.id = "private:" + episode.id;
            n.title = episode.title; n.show = episode.show; n.art = episode.art;
            n.video = episode.video; n.duration = episode.duration; n.at(at);
            n.rate = speeds.forShow(show);
            setNow(n);
            startPrivate(at);
        };
        if (web == null) begin.run();
        else web.evaluateJavascript("window.__ptv && __ptv.cmd('pause')", ignored -> begin.run());
    }

    private void startPrivate(double at) {
        if (privateEpisode == null) return;
        if (privateEpisode.video) {
            beforeVideo.run(); videoMode.accept(true);
            video.play(privateEpisode.src, at, privateEpisode.title, privateEpisode.show, speeds.forShow(show));
            ui.removeCallbacks(sync); ui.postDelayed(sync, SYNC_MS);
            return;
        }
        privatePrepared = false; privateWantsPlay = true;
        MediaPlayer player = new MediaPlayer(); privateAudio = player;
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        player.setOnPreparedListener(mp -> {
            if (privateAudio != mp) return;
            privatePrepared = true;
            if (at > 0) mp.seekTo((int) (at * 1000));
            if (privateWantsPlay && requestPrivateFocus()) {
                mp.start();
                try { mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speeds.forShow(show))); } catch (RuntimeException ignored) { }
            }
            privateChanged(); ui.removeCallbacks(privateTick); ui.post(privateTick);
        });
        player.setOnCompletionListener(mp -> {
            abandonPrivateFocus();
            privateChanged();
            if (privateEpisode != null) prefs.edit().putFloat("private_position_" + privateEpisode.id, 0).apply();
            if (sleep.atEpisodeEnd()) sleepEpisodeEnded();
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (privateAudio == mp) {
                abandonPrivateFocus();
                privateAudio = null; privatePrepared = false; mp.release();
                if (now != null) { now.playing = false; setNow(now); }
                toast("This private episode could not play. Press Play to retry, or refresh its feed.");
            }
            return true;
        });
        try { player.setDataSource(privateEpisode.src); player.prepareAsync(); }
        catch (Exception ignored) {
            player.release(); privateAudio = null;
            toast("This private episode could not load. Refresh its feed and try again.");
        }
    }

    private boolean requestPrivateFocus() {
        if (privateFocus) return true;
        AudioManager manager = (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE);
        privateFocus = manager != null && manager.requestAudioFocus(privateFocusListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!privateFocus) toast("Another app is using audio. Press Play to try again.");
        return privateFocus;
    }

    private void abandonPrivateFocus() {
        if (!privateFocus) return;
        privateFocus = false;
        AudioManager manager = (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE);
        if (manager != null) manager.abandonAudioFocus(privateFocusListener);
    }

    private void privateChanged() {
        if (privateEpisode == null || privateAudio == null || !privatePrepared || now == null) return;
        now.at(privateAudio.getCurrentPosition() / 1000.0);
        now.duration = privateAudio.getDuration() / 1000.0;
        now.playing = privateAudio.isPlaying();
        setNow(now);
    }

    private void stopPrivate() {
        if (privateEpisode == null) return;
        abandonPrivateFocus();
        if (privateAudio != null) {
            privateChanged(); privateAudio.release(); privateAudio = null;
        }
        if (video.isShowing()) video.close();
        privatePrepared = false; privateWantsPlay = false;
        ui.removeCallbacks(privateTick);
        privateEpisode = null;
        js("(__ptv.privatePlaying = false)");
    }

    private void privateCommand(String cmd) {
        if (cmd.equals("pause")) privateWantsPlay = false;
        if (privateAudio == null) {
            if (cmd.equals("play") || cmd.equals("toggle")) startPrivate(now == null ? 0 : now.position);
            return;
        }
        if (!privatePrepared) { if (cmd.equals("play") || cmd.equals("toggle")) privateWantsPlay = true; return; }
        boolean play = cmd.equals("play") || cmd.equals("toggle") && !privateAudio.isPlaying();
        if (play) { privateWantsPlay = true; if (requestPrivateFocus()) privateAudio.start(); }
        else if (cmd.equals("pause") || cmd.equals("toggle")) { privateWantsPlay = false; privateAudio.pause(); abandonPrivateFocus(); }
        else if (cmd.equals("back") || cmd.equals("prev") || cmd.equals("scanback")) seekTo(privateAudio.getCurrentPosition() / 1000.0 - 15);
        else if (cmd.equals("fwd") || cmd.equals("next") || cmd.equals("scanfwd")) seekTo(privateAudio.getCurrentPosition() / 1000.0 + 30);
        privateChanged();
    }

    /** Takes a playing video episode over from the web player into the native one. */
    void startVideo() {
        if (video.isShowing()) return;
        if (privateEpisode != null) {
            if (privateEpisode.video) startPrivate(now == null ? 0 : now.position);
            else toast("This private feed is audio only");
            return;
        }
        if (web == null) return;
        web.evaluateJavascript("window.__ptv ? __ptv.handoff() : null", r -> {
            try {
                Object v = new JSONTokener(r).nextValue();
                if (!(v instanceof String)) return;
                JSONObject o = new JSONObject((String) v);
                String artist = o.optString("artist");
                NowPlaying n = new NowPlaying();
                n.id = o.optString("id");
                n.title = o.optString("title");
                n.show = artist;
                n.art = o.optString("art");
                n.url = o.optString("url");
                n.at(o.optDouble("t", 0));
                n.duration = o.optDouble("duration", 0);
                n.video = true;
                beforeVideo.run();
                videoMode.accept(true);
                video.play(o.getString("src"), n.position, n.title, artist, speeds.forShow(artist));
                setNow(n);
                ui.removeCallbacks(sync);
                ui.postDelayed(sync, SYNC_MS);
            } catch (Exception e) {
                Log.w(MainActivity.TAG, "video handoff failed", e);
            }
        });
    }

    /** Runs a playback command in whichever player is active; also called by PlaybackService. */
    void command(String cmd) {
        if (video.isShowing()) { video.command(cmd); return; }
        if (privateEpisode != null) { privateCommand(cmd); return; }
        if (!cmd.startsWith("scan")) { js("__ptv.cmd('" + cmd + "')"); return; }
        // Audio has no on-screen player of ours: show the scan speed briefly.
        if (web == null) return;
        web.evaluateJavascript("window.__ptv ? __ptv.cmd('" + cmd + "') : 0", r -> {
            int rate;
            try { rate = (int) Double.parseDouble(r); } catch (NumberFormatException e) { return; }
            String text = rate > 0 ? "⏩  " + rate + "×" : rate < 0 ? "⏪  " + -rate + "×" : "▶  Playing";
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        });
    }

    void seekTo(double sec) {
        if (video.isShowing()) video.seekToSec(sec);
        else if (privateEpisode != null) {
            if (privateAudio != null && privatePrepared) {
                double limit = privateAudio.getDuration() / 1000.0;
                privateAudio.seekTo((int) (Math.max(0, Math.min(sec, limit)) * 1000));
            }
        } else js("__ptv.seekTo(" + sec + ")");
    }

    /** Left/right while the video player is up: skip on a click, scan on a hold. */
    boolean arrowKey(KeyEvent e) {
        int c = e.getKeyCode();
        if (c != KeyEvent.KEYCODE_DPAD_LEFT && c != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        return video.isShowing() && video.arrowKey(e);
    }

    void showVideoOsd() { if (video.isShowing()) video.showOsd(); }

    /** Back: cancels a scan, else closes the video. Returns false when no video is up. */
    boolean back() {
        if (!video.isShowing()) return false;
        if (!video.cancelScan()) video.close();
        return true;
    }

    /** The web player started or paused; a new show gets its remembered speed. */
    void webState(boolean playing, String title, String artist) {
        if (webSuspended && !playing) return;
        if (privateEpisode != null) {
            if (!playing) return;
            stopPrivate(); // a deliberate new playback in Apple's page takes over
        }
        if (artist != null && !artist.isEmpty() && !artist.equals(show)) {
            show = artist;
            setWebSpeed(speeds.forShow(artist));
        }
    }

    /** What Apple's player has (nowplaying.js), on play/pause and every few seconds. */
    void webNowPlaying(String json) {
        if (privateEpisode != null || video.isShowing() || json == null || json.isEmpty()) return;
        try {
            NowPlaying update = NowPlaying.fromJson(new JSONObject(json));
            // A freshly restored page initially reports an empty/stale paused player.
            // Retain our paused session until actual playback has started.
            if (webSuspended && !update.playing) return;
            webSuspended = false;
            setNow(update);
        } catch (JSONException e) {
            Log.w(MainActivity.TAG, "now playing: " + e);
        }
    }

    private void setNow(NowPlaying n) {
        boolean newEpisode = now == null || !n.id.equals(now.id);
        boolean newArt = now == null || !n.art.equals(now.art);
        now = n;
        if (privateEpisode != null) {
            prefs.edit().putFloat("private_position_" + privateEpisode.id, (float) n.position).apply();
        } else if (n.position > 0) n.remember(prefs);
        if (newEpisode) {
            chapters.clear();
            chaptersFor = n.id;
            if (privateEpisode == null && !n.id.isEmpty()) js("__ptv.chapters('" + n.id.replaceAll("\\D", "") + "')");
        }
        if (newArt) {
            art = null;
            Artwork.load(n.art, b -> {
                if (now == null || !n.art.equals(now.art)) return; // a newer report replaced n; same picture
                art = b;
                PlaybackService.update(activity, now, art);
                onChange.run();
            });
        }
        PlaybackService.update(activity, n, art);
        onChange.run();
    }

    /** The native player's state into {@link #now}. */
    private void videoChanged() {
        if (now == null || !video.isShowing()) return;
        now.playing = video.isPlaying();
        now.at(video.positionSec());
        double d = video.durationSec();
        if (d > 0) now.duration = d;
        now.rate = video.speed();
        setNow(now);
    }

    /** Saves the native player's position to Apple (its own player does this for audio). */
    private void savePosition(boolean finished) {
        if (privateEpisode != null || now == null || !now.video || now.id.isEmpty()) return;
        double t = finished ? now.duration : now.now();
        js("__ptv.savePosition('" + now.id.replaceAll("\\D", "") + "'," + t + "," + now.duration + ")");
    }

    // ---- chapters ------------------------------------------------------------------

    void chaptersArrived(String id, String json) {
        if (!id.equals(chaptersFor)) return;
        chapters.clear();
        chapters.addAll(Chapters.parse(json));
        onChange.run();
    }

    boolean hasChapters() { return !chapters.isEmpty(); }

    /** The chapter playing now, or null. */
    String chapterNow() {
        if (now == null) return null;
        double t = video.isShowing() ? video.positionSec() : now.now();
        Chapters.Chapter c = Chapters.at(chapters, t);
        return c == null ? null : c.name;
    }

    void showChaptersMenu() {
        if (chapters.isEmpty()) { toast("This episode has no chapters"); return; }
        double t = video.isShowing() ? video.positionSec() : now != null ? now.now() : 0;
        Chapters.showMenu(activity, chapters, t, this::seekTo);
    }

    // ---- speed -----------------------------------------------------------------------

    float speed() { return video.isShowing() ? video.speed() : privateEpisode != null ? speeds.forShow(show) : webSpeed; }

    void setSpeed(float s) {
        speeds.set(video.isShowing() ? video.show() : show, s);
        if (video.isShowing()) video.setSpeed(s);
        else if (privateEpisode != null) {
            if (privateAudio != null && privatePrepared) {
                boolean playing = privateAudio.isPlaying();
                try { privateAudio.setPlaybackParams(privateAudio.getPlaybackParams().setSpeed(s)); }
                catch (RuntimeException ignored) { toast("This speed is unavailable"); }
                if (!playing) privateAudio.pause();
                if (now != null) now.rate = s;
                privateChanged();
            }
        } else setWebSpeed(s);
    }

    void cycleSpeed() { setSpeed(Speed.next(speed())); }

    void showSpeedMenu() {
        new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Playback speed")
                .setSingleChoiceItems(Speed.labels(), Speed.indexOf(speed()), (d, which) -> {
                    setSpeed(Speed.PRESETS[which]);
                    d.dismiss();
                })
                .show();
    }

    void release() {
        sleep.set(0);
        stopPrivate();
        if (video.isShowing()) { videoChanged(); savePosition(false); }
        ui.removeCallbacks(sync);
        video.close();
    }

    // ---- sleep timer ---------------------------------------------------------------

    String sleepStatus() { return sleep.status(); }

    void showSleepMenu() {
        new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Sleep timer (" + sleep.status() + ")")
                .setSingleChoiceItems(SleepTimer.LABELS, sleep.choiceIndex(), (d, which) -> {
                    int min = SleepTimer.MINUTES[which];
                    sleep.set(min);
                    js("(__ptv.sleepAtEnd = " + (min < 0) + ")");
                    toast(min == 0 ? "Sleep timer off" : "Sleep timer: " + SleepTimer.LABELS[which].toLowerCase());
                    if (video.isShowing()) video.showOsd();
                    d.dismiss();
                })
                .show();
    }

    /** Time is up: fade out and pause whichever player is playing. */
    private void sleepNow() {
        toast("Sleep timer: pausing");
        if (video.isShowing()) video.fadeOutAndPause(FADE_MS);
        else if (privateEpisode != null) privateCommand("pause");
        else js("__ptv.fadeOutAndPause(" + FADE_MS / 1000 + ")");
    }

    /** The web player finished the episode the timer was waiting for. */
    void sleepEpisodeEnded() {
        sleep.episodeEnded();
        toast("Sleep timer: stopped at the end of the episode");
    }

    /** The native player's status line: chapter and sleep timer. */
    private String videoStatus() {
        String c = chapterNow(), s = sleep.active() ? "☾ " + sleep.status() : null;
        if (c == null) return s;
        return s == null ? c : c + "  ·  " + s;
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    private void setWebSpeed(float s) {
        webSpeed = s;
        js("__ptv.setSpeed(" + s + ")");
    }

    private void js(String script) {
        if (web != null) web.evaluateJavascript("window.__ptv && " + script, null);
    }

    private class VideoEvents implements NativeVideo.Listener {
        @Override public void onState(boolean playing, String title, String artist) {
            boolean paused = now != null && now.playing && !playing;
            videoChanged();
            if (paused) savePosition(false);
        }

        @Override public void onClosed(double at) {
            Log.i(MainActivity.TAG, "native video closed at " + at + " s");
            ui.removeCallbacks(sync);
            if (now != null) {
                now.at(at);
                now.playing = false;
                savePosition(false);
                setNow(now);
            }
            if (privateEpisode == null) js("__ptv.resumeAt(" + at + ")");
            videoMode.accept(false);
        }

        @Override public void onEnded() {
            videoChanged();
            savePosition(true);
            if (privateEpisode != null) prefs.edit().putFloat("private_position_" + privateEpisode.id, 0).apply();
            else if (now != null) NowPlaying.forget(prefs);
            if (sleep.atEpisodeEnd()) sleepEpisodeEnded();
        }

        @Override public void onFailed(double at) {
            ui.removeCallbacks(sync);
            // Fall back to the web player's own full screen for the rest of this session.
            if (privateEpisode != null) {
                if (now != null) { now.at(at); now.playing = false; setNow(now); }
                toast("This private video could not play. Refresh its feed and try again.");
            } else js("__ptv.webFallback(" + at + ")");
            videoMode.accept(false);
        }
    }
}
