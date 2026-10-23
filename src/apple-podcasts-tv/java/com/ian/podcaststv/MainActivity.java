package com.ian.podcaststv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Message;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewRenderProcess;
import android.webkit.RenderProcessGoneDetail;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * Apple's Podcasts web player (podcasts.apple.com) as a 10-foot app: the arrows move a
 * highlight between controls (or drive a pointer, if the viewer prefers), OK selects,
 * and the remote's media keys drive playback. Video episodes play full screen in
 * Android's own player, where OK plays/pauses and left/right seek.
 */
public class MainActivity extends Activity {
    static final String HOME = "https://podcasts.apple.com/";
    static final String TAG = "PodcastsTV";
    static WeakReference<MainActivity> current = new WeakReference<>(null);

    /** Desktop layout widths in CSS px; a smaller width means a larger UI. */
    private static final int[] WIDTHS = {960, 1120, 1280, 1440, 1600, 1920};
    static final int DEFAULT_WIDTH = 2;
    private static final int HOLD_FOR_MENU = 12; // key repeats (~0.6 s)
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private FrameLayout root;
    WebView web;
    private WebView popup;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private CursorView cursor;
    private PhoneKeyboard phoneKeyboard;
    Playback playback;
    private FocusNav nav;
    private ResumeCard resumeCard;
    private Screensaver saver;
    private int swallowUp = -1; // the key that woke the screensaver: its release is ignored too
    SharedPreferences prefs;
    PrivateFeeds privateFeeds;
    LibraryCache libraryCache;
    NativeLibrary library;
    private boolean libraryLoading;
    private String injectJs;
    private boolean okHeld;
    private static final long IDLE_WEB_MS = 5 * 60_000;
    private boolean foreground, idleScheduled, hibernating;
    private int idleGeneration;
    private String restoreNavigation;
    private final Runnable idleWeb = () -> { idleScheduled = false; hibernateWeb(); };
    private boolean cssVideo; // video pinned full screen by CSS (Fullscreen API refused)
    private final LoadRecovery recovery = new LoadRecovery();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Lets chrome://inspect (over adb) debug the page; adb access is already full trust.
        current = new WeakReference<>(this);
        prefs = getSharedPreferences("podcaststv", MODE_PRIVATE);
        restoreNavigation = prefs.getString("idle_navigation", null);
        injectJs = readAsset("inject.js") + "\n" + readAsset("nav.js") + "\n" + readAsset("speed.js") + "\n" + readAsset("lite.js")
                + "\n" + readAsset("bar.js") + "\n" + readAsset("nowplaying.js") + "\n" + readAsset("private-feeds.js") + "\n" + readAsset("native-library.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        cursor = new CursorView(this, this::target);
        root.addView(cursor, MATCH, MATCH);
        setContentView(root);
        playback = new Playback(this, web, root, new Speed(prefs), prefs, on -> setVideoMode(on || inVideo()), () -> { if (nav != null) nav.reset(); });
        libraryCache = new LibraryCache(this);
        privateFeeds = new PrivateFeeds(this);
        playback.setOnChange(() -> {
            keepScreenOn();
            if (web != null) ((TvWebView) web).visibilityRuleChanged();
            scheduleIdleWeb();
        });
        NowPlayingScreen screen = new NowPlayingScreen(this, playback::now, playback::art, playback::chapterNow);
        root.addView(screen, root.indexOfChild(cursor), new FrameLayout.LayoutParams(MATCH, MATCH));
        saver = new Screensaver(screen, () -> playback.audioPlaying() && !inVideo() && hasWindowFocus()
                && (resumeCard == null || !resumeCard.isShowing()) && (phoneKeyboard == null || !phoneKeyboard.isShowing()));
        library = new NativeLibrary(this);
        root.addView(library, 0, new FrameLayout.LayoutParams(MATCH, MATCH));
        if (getIntent().getData() != null) openApple(startUrl(getIntent()), false);
        else { library.showLibrary(); nativeBrowsing(); library.refreshAtStartup(); }

    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri u = intent.getData();
        if (u != null) openApple(startUrl(intent), false);
    }

    void openApple(String url, boolean forLibrary) {
        if (!apple(url)) return;
        libraryLoading = forLibrary;
        ensureWeb(false); restoreNavigation = null;
        if (!forLibrary) library.hideLibrary();
        web.setVisibility(View.VISIBLE); web.loadUrl(url);
        if (!forLibrary) web.requestFocus();
        scheduleIdleWeb();
    }

    void nativeBrowsing() {
        libraryLoading = false;
        if (web != null && !playback.audioPlaying()) web.setVisibility(View.INVISIBLE);
        scheduleIdleWeb();
    }

    void showLibrary() { library.showLibrary(); if (resumeCard != null) resumeCard.hide(); nativeBrowsing(); }

    void playApple(JSONObject episode) {
        openApple(episode.optString("url"), false);
        resumeCard.resume(episode);
    }

    private static String startUrl(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u != null && "https".equals(u.getScheme()) && "podcasts.apple.com".equals(u.getHost())) {
            return u.toString();
        }
        return HOME;
    }

    private int layoutWidth() {
        int i = prefs.getInt("width", DEFAULT_WIDTH);
        return WIDTHS[Math.max(0, Math.min(WIDTHS.length - 1, i))];
    }

    /** Our scripts and the bridge are only for Apple's Podcasts site. */
    static boolean apple(String url) {
        Uri u = url == null ? null : Uri.parse(url);
        return u != null && "https".equals(u.getScheme()) && "podcasts.apple.com".equals(u.getHost());
    }

    /** Runs a bridge call on the UI thread, if the page is still Apple's. */
    private void fromPage(Runnable r) {
        runOnUiThread(() -> { if (!hibernating && web != null && apple(web.getUrl())) r.run(); });
    }

    private void inject(WebView v) {
        if (injectJs == null || !apple(v.getUrl())) return;
        v.evaluateJavascript(injectJs.replace("__WIDTH__", String.valueOf(layoutWidth()))
                + "\n;window.__ptv && (__ptv.privatePlaying = " + (playback != null && playback.privateActive()) + ");"
                + "\n;window.__ptv && __ptv.setLite && __ptv.setLite(true);"
                + "\n;window.__ptv && (__ptv.nativeLibraryTarget = " + (library == null ? 0 : library.captureTarget(v.getUrl())) + ");", null);
    }

    private void js(String script) {
        if (web != null) web.evaluateJavascript("window.__ptv && " + script, null);
    }

    // ---- WebView clients -------------------------------------------------

    private class MainClient extends WebViewClient {
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Intentional idle termination concerns only the already-detached page.
            return view != web || hibernating;
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            String scheme = r.getUrl().getScheme();
            return !"https".equals(scheme) && !"http".equals(scheme); // ignore itms:/podcasts: deep links
        }

        @Override public void onPageStarted(WebView v, String url, Bitmap favicon) { inject(v); }
        @Override public void onPageCommitVisible(WebView v, String url) { inject(v); }

        @Override
        public void onPageFinished(WebView v, String url) {
            inject(v);
            CookieManager.getInstance().flush();
            if (restoreNavigation != null && apple(url)) {
                String state = restoreNavigation; restoreNavigation = null;
                v.evaluateJavascript("window.__ptv && __ptv.restoreNavigation(" + state + ")", null);
            }
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            recovery.onError(v, r, e);
        }
    }

    private class MainChrome extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            closePopup();
            popup = WebSetup.create(MainActivity.this, false);
            popup.setWebViewClient(new WebViewClient());
            popup.setWebChromeClient(new WebChromeClient() {
                @Override public void onCloseWindow(WebView w) { closePopup(); }
            });
            root.addView(popup, root.indexOfChild(cursor), new FrameLayout.LayoutParams(MATCH, MATCH));
            popup.requestFocus();
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView w) {
            if (w == popup) closePopup();
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            for (String res : request.getResources()) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                    request.grant(new String[]{res});
                    return;
                }
            }
            request.deny();
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            hideCustomView();
            customView = view;
            customCallback = callback;
            root.addView(view, root.indexOfChild(cursor), new FrameLayout.LayoutParams(MATCH, MATCH));
            setVideoMode(true);
        }

        @Override
        public void onHideCustomView() { hideCustomView(); }
    }

    private void closePopup() {
        if (popup == null) return;
        root.removeView(popup);
        popup.destroy();
        popup = null;
        web.requestFocus();
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        setVideoMode(cssVideo);
    }

    private boolean inVideo() {
        return customView != null || cssVideo || playback.videoShowing();
    }

    boolean pointerMode() {
        return prefs.getBoolean("pointer", false);
    }

    private void setVideoMode(boolean on) {
        if (on) cursor.hideNow();
        else if (library != null) library.restoreFocus();
        keepScreenOn();
    }

    /** Video, or audio playing: the TV must not sleep (its standby turns Wi-Fi off). */
    private void keepScreenOn() {
        if (inVideo() || playback.audioPlaying()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void exitVideo() {
        js("__ptv.exitFullscreen()"); // also remembers not to re-enter for this episode
        hideCustomView();
    }

    private class Bridge {
        @JavascriptInterface public void nativeLibrary(String json) { fromPage(() -> library.receive(json)); }
        @JavascriptInterface
        public void privateLibrary() { fromPage(() -> privateFeeds.publish()); }
        @JavascriptInterface
        public void privateShow(String id) { fromPage(() -> privateFeeds.openInLibrary(id)); }
        @JavascriptInterface
        public void privatePlay(String feed, String episode) { fromPage(() -> privateFeeds.playFromLibrary(feed, episode)); }

        @JavascriptInterface
        public void openPrivateFeeds() { fromPage(() -> privateFeeds.show()); }

        @JavascriptInterface
        public void importPrivateFeeds(String links) { fromPage(() -> privateFeeds.importLinks(links)); }

        @JavascriptInterface
        public void onState(boolean playing, String title, String artist) {
            fromPage(() -> playback.webState(playing, title, artist));
        }

        @JavascriptInterface
        public void onNowPlaying(String json) {
            fromPage(() -> playback.webNowPlaying(json));
        }

        @JavascriptInterface
        public void onChapters(String id, String json) {
            fromPage(() -> playback.chaptersArrived(id, json));
        }

        @JavascriptInterface
        public void onVideo() {
            fromPage(() -> { if (!inVideo()) playback.startVideo(); });
        }

        @JavascriptInterface
        public void onFocusAt(double x, double y, double vw) {
            fromPage(() -> nav.focusedAt(x, y, vw));
        }

        @JavascriptInterface
        public void onSleepEnded() {
            fromPage(playback::sleepEpisodeEnded);
        }

        @JavascriptInterface
        public void onVideoMode(boolean on) {
            fromPage(() -> { cssVideo = on; setVideoMode(inVideo()); });
        }

        @JavascriptInterface
        public void onSignInSheet(boolean open) {
            fromPage(() -> {
                if (open) {
                    recovery.signInOpened();
                    phoneKeyboard.show(true);
                } else {
                    nav.reset(); // the sheet is gone: arrows must not stay inside its login frame
                    if (phoneKeyboard.isAuto()) phoneKeyboard.hide();
                }
            });
        }
    }

    // ---- Remote input ----------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int repeat = e.getRepeatCount();
        boolean video = inVideo();
        if (e.getKeyCode() == swallowUp) { if (!down) swallowUp = -1; return true; }
        if (down && saver.key()) { swallowUp = e.getKeyCode(); return true; }
        if (library != null && library.visible() && !video) {
            int key=e.getKeyCode();
            if (key==KeyEvent.KEYCODE_BACK || key==KeyEvent.KEYCODE_ESCAPE) {
                if (!down && !library.back()) moveTaskToBack(true); return true;
            }
            if (key==KeyEvent.KEYCODE_MENU || key==KeyEvent.KEYCODE_INFO) { if(!down)AppMenu.show(this); return true; }
            if (!(key >= KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && key <= KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) && key != KeyEvent.KEYCODE_MEDIA_PLAY && key != KeyEvent.KEYCODE_MEDIA_PAUSE) return super.dispatchKeyEvent(e);
        }
        if (resumeCard != null && resumeCard.key(e)) return true;
        if (down && repeat == 0) js("__ptv.bar && __ptv.bar.poke()"); // remote used: show the player bar
        if (playback.arrowKey(e)) return true; // left/right: skip, or hold to scan
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) arrow(e.getKeyCode(), repeat, video);
                return true;

            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (isTypingKeyboard(e)) break; // a real keyboard's Enter goes to the page
                // fall through
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (down) {
                    if (repeat == HOLD_FOR_MENU && !okHeld) {
                        okHeld = true;
                        AppMenu.show(this);
                    }
                } else {
                    if (!okHeld) {
                        if (video) media("toggle");
                        else if (pointerMode()) clickUnderPointer();
                        else nav.select();
                    }
                    okHeld = false;
                }
                return true;

            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (down) cursor.scroll(8); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) cursor.scroll(-8); return true;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                if (!down) AppMenu.show(this); return true;
            case KeyEvent.KEYCODE_PROG_GREEN:
                if (!down) togglePhoneKeyboard(); return true;
            case KeyEvent.KEYCODE_PROG_YELLOW:
                if (!down) playback.cycleSpeed(); return true;
            case KeyEvent.KEYCODE_PROG_BLUE:
                if (!down) playback.showChaptersMenu(); return true;

            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down) back(); return true;

            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:  return mediaKey(e, "toggle");
            case KeyEvent.KEYCODE_MEDIA_PLAY:   return mediaKey(e, "play");
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:   return mediaKey(e, "pause");
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return mediaKey(e, "scanfwd");
            case KeyEvent.KEYCODE_MEDIA_REWIND: return mediaKey(e, "scanback");
            case KeyEvent.KEYCODE_MEDIA_NEXT:   return mediaKey(e, "next");
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return mediaKey(e, "prev");
        }
        return super.dispatchKeyEvent(e);
    }

    /** Enter from a keyboard (hardware or on-screen) belongs to the page, not the pointer. */
    private static boolean isTypingKeyboard(KeyEvent e) {
        if ((e.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return true;
        if (e.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) return true;
        InputDevice d = e.getDevice();
        return d != null && d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private void arrow(int code, int repeat, boolean video) {
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (video) {
            if (repeat == 0 && dx != 0) media(dx < 0 ? "back" : "fwd");
            else if (dy != 0) playback.showVideoOsd();
            return;
        }
        if (pointerMode()) cursor.nudge(dx, dy, repeat);
        else nav.move(dx, dy);
    }

    /** Pointer mode: popups get a real tap; the page is pressed through nav.js. */
    private void clickUnderPointer() {
        cursor.moveTo(cursor.pointerX(), cursor.pointerY());
        if (target() != web) cursor.click();
        else nav.clickAt(cursor.pointerX(), cursor.pointerY());
    }

    private boolean mediaKey(KeyEvent e, String cmd) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) media(cmd);
        return true;
    }

    /** Runs a playback command in the page; also called by PlaybackService. */
    void media(String cmd) {
        if (playback.privateActive()) { playback.command(cmd); return; }
        if (cmd.equals("pause")) { if (resumeCard != null) resumeCard.cancelResume(); if (web == null) return; }
        if ((cmd.equals("play") || cmd.equals("toggle")) && playback.webNeedsResume()) {
            ensureWeb(false);
            JSONObject episode = NowPlaying.last(prefs);
            if (episode != null && apple(episode.optString("url"))) {
                restoreNavigation = null;
                resumeCard.resume(episode);
            }
            return;
        }
        if (web == null) {
            if (!cmd.equals("play") && !cmd.equals("toggle")) return;
            ensureWeb(true);
        }
        playback.command(cmd);
    }

    private View target() {
        return popup != null ? popup : web;
    }

    void togglePhoneKeyboard() {
        if (phoneKeyboard == null) { openApple(HOME, false); }
        if (phoneKeyboard.isShowing()) phoneKeyboard.hide(); else phoneKeyboard.show(false);
    }

    private void back() {
        if (playback.back()) return;
        if (inVideo()) { exitVideo(); return; }
        if (nav != null && nav.leaveFrame()) return;
        if (popup != null) {
            if (popup.canGoBack()) popup.goBack(); else closePopup();
            return;
        }
        if (phoneKeyboard.isShowing() && !phoneKeyboard.isAuto()) { phoneKeyboard.hide(); return; }
        // Close an open sheet first, then walk history, then leave (audio keeps playing).
        web.evaluateJavascript("window.__ptv ? __ptv.escape() : false", closed -> {
            if ("true".equals(closed)) return;
            // These links are activated by evaluateJavascript for the TV remote.
            // Chromium's browser-UI back skips their entries as lacking a page
            // gesture; DOM history.back follows the actual preceding SPA route.
            if (web.copyBackForwardList().getCurrentIndex() > 0) web.evaluateJavascript("history.back()", null);
            else showLibrary();
        });
    }

    boolean sharp() { return prefs.getBoolean("sharp", true); } // full-resolution text by default

    void applyRenderScale() {
        if (web == null) return;
        WebSetup.renderScale(this, web, sharp());
        js("__ptv.setLite && __ptv.setLite(true)"); // no frosted glass in either mode: it cost the most
    }

    void setPointerMode(boolean on) {
        prefs.edit().putBoolean("pointer", on).apply();
        if (nav != null) nav.reset();
        if (on) cursor.moveTo(cursor.getWidth() / 2f, cursor.getHeight() / 2f); else cursor.hideNow();
    }

    void setWidthIndex(int i) {
        i = Math.max(0, Math.min(WIDTHS.length - 1, i));
        prefs.edit().putInt("width", i).apply();
        if (web != null) inject(web);
    }

    void quit() {
        PlaybackService.stop(this);
        finishAndRemoveTask();
    }

    // ---- Lifecycle --------------------------------------------------------

    private String savedPage() {
        String page = prefs.getString("idle_page", HOME);
        return apple(page) ? page : HOME;
    }

    private boolean canHibernate() {
        return (!foreground || library != null && library.visible() && !libraryLoading)
                && !hibernating && web != null && popup == null && !inVideo()
                && (playback.privateActive() || playback.now() == null || !playback.now().playing);
    }

    private void scheduleIdleWeb() {
        if (!canHibernate()) {
            root.removeCallbacks(idleWeb); idleScheduled = false; idleGeneration++;
        } else if (!idleScheduled) {
            idleScheduled = true; root.postDelayed(idleWeb, library != null && library.visible() && !libraryLoading ? 1000 : IDLE_WEB_MS);
        }
    }

    private void hibernateWeb() {
        if (!canHibernate()) return;
        final WebView old = web;
        final int generation = idleGeneration;
        final String page = old.getUrl();
        old.evaluateJavascript("window.__ptv && __ptv.captureNavigation ? __ptv.captureNavigation() : null", result -> {
            if (generation != idleGeneration || !canHibernate() || web != old) return;
            hibernating = true;
            String state = "null".equals(result) ? null : result;
            String saved = apple(page) ? page : HOME;
            // WebView.getUrl can lag a same-document history.pushState (private shows).
            try {
                String path = new JSONObject(state == null ? "{}" : state).optString("path");
                if (path.startsWith("/") && !path.startsWith("//")) {
                    Uri base = Uri.parse(saved);
                    String candidate = base.getScheme() + "://" + base.getAuthority() + path;
                    if (apple(candidate)) saved = candidate;
                }
            } catch (org.json.JSONException ignored) { }
            prefs.edit().putString("idle_page", saved)
                    .putString("idle_navigation", state).apply();
            restoreNavigation = state;
            CookieManager.getInstance().flush();
            resumeCard.cancelResume(); resumeCard.hide(); phoneKeyboard.hide(); nav.reset();
            playback.detachIdleWeb();
            WebViewRenderProcess renderer = Build.VERSION.SDK_INT >= 29 ? old.getWebViewRenderProcess() : null;
            root.removeView(old); old.stopLoading(); old.removeJavascriptInterface("PodcastsTV"); old.destroy();
            web = null;
            if (renderer != null) renderer.terminate();
            // These objects hold the old WebView and auth-frame reply proxies.
            nav = null; phoneKeyboard = null; resumeCard = null;
            hibernating = false;
            Log.i(TAG, "Idle WebView released; paused media session retained");
        });
    }

    private void ensureWeb(boolean loadPage) {
        if (web != null) return;
        WebView.setWebContentsDebuggingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web = WebSetup.create(this, true);
        web.setWebViewClient(new MainClient()); web.setWebChromeClient(new MainChrome());
        web.addJavascriptInterface(new Bridge(), "PodcastsTV"); root.addView(web, 0, new FrameLayout.LayoutParams(MATCH, MATCH));
        applyRenderScale();
        AuthBridge auth = new AuthBridge(); auth.install(web, readAsset("auth.js"));
        phoneKeyboard = new PhoneKeyboard(this, root, readAsset("phone.html"), new PhoneTyping(web, this::target, auth));
        nav = new FocusNav(web, cursor, this::target); nav.useAuth(auth);
        resumeCard = new ResumeCard(this, root, web);
        playback.attachWeb(web); ((TvWebView) web).stayVisible = playback::audioLoaded;
        ((TvWebView) web).visibilityRuleChanged();
        web.requestFocus();
        if (loadPage) web.loadUrl(savedPage());
        Log.i(TAG, "Idle WebView recreated");
    }

    @Override protected void onStart() {
        super.onStart(); foreground = true; idleGeneration++;
        root.removeCallbacks(idleWeb); idleScheduled = false;
        if (library == null || !library.visible()) ensureWeb(true);
        else scheduleIdleWeb();
    }

    @Override protected void onStop() {
        super.onStop(); foreground = false; scheduleIdleWeb();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Deliberately not pausing the WebView: audio keeps playing in the background.
        if (web != null) CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        root.removeCallbacks(idleWeb); idleGeneration++;
        saver.stop();
        playback.release();
        PlaybackService.stop(this);
        if (phoneKeyboard != null) phoneKeyboard.hide();
        privateFeeds.close();
        libraryCache.close();
        if (current.get() == this) current = new WeakReference<>(null);
        closePopup();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private String readAsset(String name) { return WebSetup.asset(this, name); }
}
