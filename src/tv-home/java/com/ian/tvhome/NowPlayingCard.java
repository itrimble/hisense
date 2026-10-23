package com.ian.tvhome;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;

import java.security.MessageDigest;

/**
 * "Now Playing" on the top shelf, as tvOS shows it: what the Podcasts app is playing, with
 * its artwork and progress. The Podcasts app (com.ian.podcaststv) broadcasts its state to
 * this package; the launcher asks for it whenever it comes to the front.
 *
 * Any app can send that broadcast, so a report counts only if its "sender" extra is a
 * PendingIntent created by com.ian.podcaststv (Android sets the creator; it can't be faked)
 * and the installed com.ian.podcaststv is signed with the Podcasts app's key.
 */
final class NowPlayingCard {
    static final String STATE = "com.ian.tvhome.NOW_PLAYING";
    static final String REQUEST = "com.ian.podcaststv.NOW_PLAYING_REQUEST";
    static final String PODCASTS = "com.ian.podcaststv";
    private static final float X = 96, Y = 400, WIDTH = 760, HEIGHT = 172, ART = 132;
    /** SHA-256 of the Podcasts app's signing certificate (~/.android/podcasts-tv.keystore). */
    private static final String PODCASTS_CERT =
            "b9818c61d7159f5c3794099c18fba876e86522121cb5af1abbb157e209454e9e";
    private static final int MAX_TEXT = 300, MAX_ART = 400;

    private final HomeView v;
    private String title, show;
    private boolean playing, video;
    private double position, duration;
    private long at;   // uptime of the last report, to move the progress bar along
    private Bitmap art;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final RectF r = new RectF();

    final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (fromPodcasts(c, i)) update(i); }
    };

    NowPlayingCard(HomeView v) { this.v = v; }

    static IntentFilter filter() { return new IntentFilter(STATE); }

    /** Asks the Podcasts app to report what it is playing. */
    static void request(Context c) {
        c.sendBroadcast(new Intent(REQUEST).setPackage(PODCASTS));
    }

    boolean active() { return title != null; }

    private static boolean fromPodcasts(Context c, Intent i) {
        try {
            PendingIntent sender = i.getParcelableExtra("sender");
            return sender != null && PODCASTS.equals(sender.getCreatorPackage()) && signedByPodcastsKey(c);
        } catch (RuntimeException e) {   // a malformed parcel from some other app
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean signedByPodcastsKey(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo p = pm.getPackageInfo(PODCASTS, PackageManager.GET_SIGNING_CERTIFICATES);
                if (p.signingInfo == null || p.signingInfo.hasMultipleSigners()) return false;
                sigs = p.signingInfo.getApkContentsSigners();
            } else {
                sigs = pm.getPackageInfo(PODCASTS, PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs == null || sigs.length != 1) return false;
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return PODCASTS_CERT.equals(hex.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private static String clip(String s) {
        return s == null || s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT);
    }

    private static double seconds(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) || v < 0 ? 0 : v;
    }

    private void update(Intent i) {
        String t = i.getStringExtra("title");
        if (t == null || t.isEmpty()) {
            title = null;
            art = null;
        } else {
            title = clip(t);
            show = clip(i.getStringExtra("show"));
            playing = i.getBooleanExtra("playing", false);
            video = i.getBooleanExtra("video", false);
            position = seconds(i.getDoubleExtra("position", 0));
            duration = seconds(i.getDoubleExtra("duration", 0));
            at = SystemClock.uptimeMillis();
            Bitmap b = i.getParcelableExtra("art");
            if (b != null && b.getWidth() > 0 && b.getHeight() > 0) {
                art = b.getWidth() > MAX_ART || b.getHeight() > MAX_ART
                        ? Bitmap.createScaledBitmap(b, MAX_ART, MAX_ART, true) : b;
            }
        }
        v.invalidate();
    }

    /** Drawn on the shelf's 1920x1080 canvas, under the app's title, at {@code alpha}. */
    void draw(Canvas c, float alpha) {
        if (title == null || alpha < 0.01f) return;
        int a255 = Math.round(255 * alpha);
        r.set(X, Y, X + WIDTH, Y + HEIGHT);
        paint.setShader(null);
        paint.setColor(0xFF2C2C30);
        paint.setAlpha(Math.round(190 * alpha));
        c.drawRoundRect(r, 26, 26, paint);

        float ax = X + 20, ay = Y + (HEIGHT - ART) / 2;
        r.set(ax, ay, ax + ART, ay + ART);
        if (art != null) {
            BitmapShader s = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            m.setScale(ART / art.getWidth(), ART / art.getHeight());
            m.postTranslate(ax, ay);
            s.setLocalMatrix(m);
            paint.setShader(s);
            paint.setAlpha(a255);
        } else {
            paint.setColor(0xFF48484A);
            paint.setAlpha(a255);
        }
        c.drawRoundRect(r, 14, 14, paint);
        paint.setShader(null);

        float x = ax + ART + 28, w = X + WIDTH - 28 - x;
        text.setTypeface(v.fonts.semibold);
        text.setTextSize(21);
        text.setLetterSpacing(0.08f);
        text.setColor(0xFFFFFFFF);
        text.setAlpha(Math.round(150 * alpha));
        c.drawText(playing ? "NOW PLAYING" : "PAUSED", x, Y + 50, text);
        text.setLetterSpacing(0);
        text.setTypeface(v.fonts.semibold);
        text.setTextSize(31);
        text.setAlpha(a255);
        c.drawText(TextUtils.ellipsize(title, text, w, TextUtils.TruncateAt.END).toString(), x, Y + 92, text);
        text.setTypeface(v.fonts.text);
        text.setTextSize(25);
        text.setAlpha(Math.round(170 * alpha));
        if (show != null) c.drawText(TextUtils.ellipsize(show, text, w, TextUtils.TruncateAt.END).toString(), x, Y + 126, text);

        if (duration > 0) {
            double p = position + (playing ? (SystemClock.uptimeMillis() - at) / 1000.0 : 0);
            float f = (float) Math.max(0, Math.min(1, p / duration));
            r.set(x, Y + 146, x + w, Y + 152);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(55 * alpha));
            c.drawRoundRect(r, 3, 3, paint);
            r.right = x + w * f;
            paint.setAlpha(Math.round(220 * alpha));
            c.drawRoundRect(r, 3, 3, paint);
        }
    }
}
