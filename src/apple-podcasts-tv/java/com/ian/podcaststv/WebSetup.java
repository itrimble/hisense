package com.ian.podcaststv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WebViews set up as a desktop Chrome window that a TV remote can drive. */
final class WebSetup {
    private WebSetup() { }

    static WebView create(Context c, boolean allowPopups) {
        WebView w = new TvWebView(c);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(allowPopups);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUserAgentString(desktopUserAgent(c));
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(Color.BLACK);
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);
        return w;
    }

    /**
     * Smooth mode lays the page out at 1280x720 and scales it up 1.5x on the GPU: Chrome
     * then rasterises 2.25x fewer pixels, which this TV (1.8 GB RAM, "tile memory limits
     * exceeded" at 1080p) needs for a usable frame rate. Sharp mode draws at full 1080p.
     */
    static void renderScale(Activity a, WebView web, boolean sharp) {
        Point size = new Point();
        a.getWindowManager().getDefaultDisplay().getRealSize(size);
        float s = sharp ? 1f : 1.5f;
        web.setLayoutParams(new FrameLayout.LayoutParams(Math.round(size.x / s), Math.round(size.y / s)));
        web.setPivotX(0);
        web.setPivotY(0);
        web.setScaleX(s);
        web.setScaleY(s);
    }

    /** Apple serves the full web player to desktop Chrome; keep the real engine version. */
    private static String desktopUserAgent(Context c) {
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(WebSettings.getDefaultUserAgent(c));
        String major = m.find() ? m.group(1) : "140";
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + major + ".0.0.0 Safari/537.36";
    }

    /** A text file from the app's assets, or null. */
    static String asset(Context c, String name) {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return null;
        }
    }
}
