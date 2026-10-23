package com.ian.podcaststv;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

/**
 * What to do when something on podcasts.apple.com fails to load. This network has
 * intermittent DNS misses and IPv6 route drops, and Apple's page does not retry.
 */
final class LoadRecovery {
    private static final int MAX_AUTH_RETRIES = 3;

    private int authRetries;
    private int reloads;
    private long windowStart;
    private boolean pending;

    /** A new sign-in sheet gets a fresh set of retries. */
    void signInOpened() { authRetries = 0; }

    void onError(WebView v, WebResourceRequest r, WebResourceError e) {
        Uri u = r.getUrl();
        Log.w(MainActivity.TAG, "load failed " + e.getErrorCode() + " " + e.getDescription() + " " + u);
        // Apple's sign-in sheet spins forever if its login frame fails once (e.g. a DNS hiccup).
        if ("idmsa.apple.com".equals(u.getHost()) && u.getPath() != null
                && u.getPath().startsWith("/appleauth/auth/authorize") && authRetries < MAX_AUTH_RETRIES) {
            authRetries++;
            v.postDelayed(() -> v.evaluateJavascript("window.__ptv && __ptv.retryFrames('idmsa.apple.com')", null),
                    1500L * authRetries);
        }
        // A failed player script leaves the page half-built (dead buttons); reload it once
        // the network has had a moment.
        String path = u.getPath() == null ? "" : u.getPath();
        if (!r.isForMainFrame() && "podcasts.apple.com".equals(u.getHost()) && path.endsWith(".js")) {
            long now = SystemClock.uptimeMillis();
            if (now - windowStart > 5 * 60_000) { windowStart = now; reloads = 0; }
            if (reloads < 2 && !pending) {
                reloads++;
                pending = true;
                v.postDelayed(() -> { pending = false; v.reload(); }, 4000);
            }
        }
        if (!r.isForMainFrame()) return;
        String html = "<body style='background:#000;color:#ddd;font:28px sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:90vh;text-align:center'>"
                + "<div>Can't reach Apple Podcasts.<br><br>"
                + "<button style='font-size:28px;padding:12px 32px' onclick=\"location.href='" + MainActivity.HOME + "'\">"
                + "Try again</button></div></body>";
        v.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }
}
