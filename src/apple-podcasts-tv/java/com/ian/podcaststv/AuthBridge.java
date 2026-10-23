package com.ian.podcaststv;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A line into Apple's cross-origin sign-in frame. assets/auth.js is added to frames
 * from https://idmsa.apple.com only, and phone-typed text is handed to it there; the
 * page script cannot reach that frame, and native key presses never reached the box.
 */
final class AuthBridge {
    private static final Set<String> ORIGIN = Collections.singleton("https://idmsa.apple.com");
    private final List<JavaScriptReplyProxy> frames = new ArrayList<>();
    private boolean available;
    private java.util.function.Consumer<JSONObject> replies;

    /** Messages back from the helper (remote navigation reaching an edge, a box selected). */
    void onReply(java.util.function.Consumer<JSONObject> r) { replies = r; }

    void install(WebView web, String helperJs) {
        if (helperJs == null
                || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(MainActivity.TAG, "sign-in helper unavailable on this WebView");
            return;
        }
        WebViewCompat.addWebMessageListener(web, "PodcastsTVAuth", ORIGIN,
                (view, message, origin, isMainFrame, reply) -> {
                    String data = message.getData();
                    if (!isMainFrame && isApple(origin) && data != null && data.startsWith("{") && replies != null) {
                        try { replies.accept(new JSONObject(data)); } catch (JSONException ignored) { }
                        return;
                    }
                    if (!isMainFrame && "ready".equals(data) && isApple(origin)) {
                        if (frames.size() > 8) frames.remove(0);
                        frames.add(reply);
                        Log.i(MainActivity.TAG, "sign-in helper connected (" + frames.size() + " frame(s))");
                    }
                });
        WebViewCompat.addDocumentStartJavaScript(web, helperJs, ORIGIN);
        available = true;
    }

    /** True once a sign-in frame has loaded the helper. */
    boolean connected() { return available && !frames.isEmpty(); }

    /** Sends text, or a key ("enter", "backspace", "tab"), to the sign-in frame. */
    void send(String type, String text) { send(type, text, null); }

    /** As above, with a direction for remote navigation ("up", "down", "left", "right"). */
    void send(String type, String text, String dir) {
        JSONObject m = new JSONObject();
        try {
            m.put("type", type);
            if (text != null) m.put("text", text);
            if (dir != null) m.put("dir", dir);
        } catch (JSONException ignored) {
            return;
        }
        String data = m.toString();
        for (int i = frames.size() - 1; i >= 0; i--) {
            try {
                frames.get(i).postMessage(data);
            } catch (RuntimeException e) {
                frames.remove(i); // that frame is gone
            }
        }
    }

    private static boolean isApple(Uri origin) {
        return origin != null && "https".equals(origin.getScheme()) && "idmsa.apple.com".equals(origin.getHost());
    }
}
