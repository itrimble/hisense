package com.ian.podcaststv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * The MediaSession, with the episode, its artwork and position, so the remote's media
 * keys work from anywhere and a home screen can show what is playing. The session lives
 * as long as the process, not the service: a paused episode keeps it, so the remote's
 * play key still resumes it from another app.
 *
 * The service keeps the process in the foreground. While playing it also holds the CPU
 * and Wi-Fi awake: with the screen off this TV otherwise drops into a standby that turns
 * Wi-Fi off and the stream stops. It stays (without the locks) for a while after a pause:
 * a backgrounded app with no service is cached, and a cached process may be frozen or cut
 * off from the network, so the play key could no longer restart the stream.
 */
public class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final long PAUSED_KEEP_MS = 30 * 60_000;
    private static boolean running;

    private static MediaSession session;
    private static NowPlaying current;  // for the launcher's "what is playing?" request
    private static Bitmap currentArt, cardArt;

    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable pausedTooLong = this::stopSelf;

    static void update(Context c, NowPlaying np, Bitmap art) {
        if (art != currentArt) {
            currentArt = art;
            cardArt = art == null ? null : Bitmap.createScaledBitmap(art, 200, 200, true);
        }
        current = np;
        publish(c.getApplicationContext(), np);
        announce(c);
        if (np.playing || running) {
            Intent i = new Intent(c, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        }
    }

    /**
     * Tells the TV Home launcher what is playing (no title: nothing is). "sender" is a
     * PendingIntent the launcher checks the creator of, so other apps can't fake the card;
     * it is never sent.
     */
    static void announce(Context c) {
        Intent i = new Intent("com.ian.tvhome.NOW_PLAYING").setPackage("com.ian.tvhome");
        i.putExtra("sender", PendingIntent.getBroadcast(c, 0,
                new Intent("com.ian.podcaststv.NOW_PLAYING_SENDER").setPackage(c.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE));
        NowPlaying n = current;
        if (n != null) {
            i.putExtra("title", cap(n.title)).putExtra("show", cap(n.show)).putExtra("playing", n.playing)
                    .putExtra("position", n.now()).putExtra("duration", n.duration).putExtra("video", n.video);
            if (cardArt != null) i.putExtra("art", cardArt);
        }
        c.sendBroadcast(i);
    }

    private static String cap(String s) { return s.length() > 300 ? s.substring(0, 300) : s; }

    /** The launcher came to the front and asks what is playing. */
    public static class Request extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) { announce(c); }
    }

    static void stop(Context c) {
        current = null;
        announce(c);
        if (running) c.stopService(new Intent(c, PlaybackService.class));
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
    }

    private static MediaSession session(Context app) {
        if (session != null) return session;
        session = new MediaSession(app, "PodcastsTV");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { send("play"); }
            @Override public void onPause() { send("pause"); }
            @Override public void onStop() { send("pause"); }
            @Override public void onSkipToNext() { send("next"); }
            @Override public void onSkipToPrevious() { send("prev"); }
            @Override public void onFastForward() { send("fwd"); }
            @Override public void onRewind() { send("back"); }
            @Override public void onSeekTo(long ms) { send("seekto:" + ms / 1000.0); }
        });
        session.setSessionActivity(openApp(app));
        session.setActive(true);
        return session;
    }

    private static void publish(Context app, NowPlaying n) {
        MediaSession s = session(app);
        String title = title(app, n);
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, n.show)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, n.show);
        if (n.duration > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (n.duration * 1000));
        if (!n.art.isEmpty()) b.putString(MediaMetadata.METADATA_KEY_ART_URI, n.art);
        if (currentArt != null) b.putBitmap(MediaMetadata.METADATA_KEY_ART, currentArt);
        s.setMetadata(b.build());
        s.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(n.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        (long) (n.now() * 1000), n.playing ? n.rate : 0f, SystemClock.elapsedRealtime())
                .build());
    }

    private static String title(Context c, NowPlaying n) {
        return n.title.isEmpty() ? c.getString(R.string.app_name) : n.title;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PodcastsTV:playback");
        cpu.setReferenceCounted(false);
        wifi = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PodcastsTV:playback");
        wifi.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NowPlaying n = current;
        // startForegroundService() promises a startForeground(), even when there is nothing to show.
        startForeground(NOTIFICATION_ID, notification(n == null ? getString(R.string.app_name) : title(this, n),
                n == null ? "" : n.show));
        ui.removeCallbacks(pausedTooLong);
        if (n == null) { stopSelf(); return START_NOT_STICKY; }
        if (n.playing) {
            cpu.acquire(4 * 60 * 60_000L); // renewed by every update; a safety net if updates stop
            wifi.acquire();
        } else {
            releaseLocks();
            ui.postDelayed(pausedTooLong, PAUSED_KEEP_MS);
        }
        return START_NOT_STICKY;
    }

    private void releaseLocks() {
        if (cpu.isHeld()) cpu.release();
        if (wifi.isHeld()) wifi.release();
    }

    private Notification notification(String title, String artist) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(openApp(this))
                .setOngoing(true)
                .setStyle(new Notification.MediaStyle().setMediaSession(session(getApplicationContext()).getSessionToken()))
                .build();
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE);
    }

    private static void send(String cmd) {
        MainActivity a = MainActivity.current.get();
        if (a != null) a.media(cmd);
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacks(pausedTooLong);
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
