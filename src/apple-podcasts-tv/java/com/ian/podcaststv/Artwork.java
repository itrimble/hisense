package com.ian.podcaststv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Episode artwork from Apple's image CDN, one image cached (the TV is short of memory). */
final class Artwork {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static String cachedUrl;
    private static Bitmap cached;

    private Artwork() { }

    /** Calls back on the main thread with the image, or null if it could not be loaded. */
    static void load(String url, Consumer<Bitmap> done) {
        if (url == null || !url.startsWith("https://")) { done.accept(null); return; }
        synchronized (Artwork.class) {
            if (url.equals(cachedUrl) && cached != null) { done.accept(cached); return; }
        }
        IO.execute(() -> {
            Bitmap b = fetch(url);
            synchronized (Artwork.class) {
                if (b != null) { cachedUrl = url; cached = b; }
            }
            UI.post(() -> done.accept(b));
        });
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory; artwork has no alpha
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "artwork " + url + ": " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
