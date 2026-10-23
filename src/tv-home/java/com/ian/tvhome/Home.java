package com.ian.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An Apple TV-style home screen for Android TV. */
public class Home extends Activity {
    static final String TAG = "TvHome";
    private static final String OPEN = "Open", MOVE = "Move", HIDE = "Hide", UNHIDE = "Unhide",
            INFO = "App Info", SHOW_HIDDEN = "Show Hidden Apps", HIDE_HIDDEN = "Stop Showing Hidden Apps",
            SOUND_OFF = "Turn Sounds Off", SOUND_ON = "Turn Sounds On";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private HomeView view;
    private Apps apps;
    private Sounds sounds;
    private SharedPreferences prefs;
    private List<Apps.App> all = new ArrayList<>();
    private boolean dirty = true, longPressed;
    private int loadGen;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dirty = true; if (hasWindowFocus()) reload(); }
    };

    private final Runnable clock = new Runnable() {
        @Override public void run() { view.invalidate(); ui.postDelayed(this, 15_000); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        float k = Math.max(dm.widthPixels, dm.heightPixels) / HomeView.W;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        Fonts fonts = new Fonts(this);
        sounds = new Sounds(this);
        sounds.setEnabled(prefs.getBoolean("sounds", true));
        apps = new Apps(this, Math.round(HomeView.TILE_W * k), Math.round(HomeView.TILE_H * k),
                Math.round(HomeView.HERO_W * k), Math.round(HomeView.HERO_H * k));
        view = new HomeView(this, k, fonts);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(view);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        registerReceiver(view.overlay.nowPlaying.receiver, NowPlayingCard.filter());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dirty) reload();
        NowPlayingCard.request(this);
        ui.post(clock);
        view.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(clock);
        endMove();
        if (view.overlay.menuOpen()) view.overlay.closeMenu();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        unregisterReceiver(view.overlay.nowPlaying.receiver);
        worker.shutdownNow();
        sounds.release();
        super.onDestroy();
    }

    /** Pressing Home while already home returns to the top row, as on tvOS. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!hasWindowFocus()) return;
        if (view.overlay.menuOpen()) view.overlay.closeMenu();
        endMove();
        view.scrollToTop();
    }

    private void reload() {
        dirty = false;
        final int gen = ++loadGen;
        worker.execute(() -> {
            final List<Apps.App> list;
            try {
                list = apps.load();
            } catch (RuntimeException e) {
                Log.e(TAG, "loading apps failed", e);
                return;
            }
            ui.post(() -> {
                if (gen != loadGen) return;
                all = list;
                view.setApps(all, null);
            });
        });
    }

    // ---- remote ---------------------------------------------------------------

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return arrow(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN: return arrow(0, 1);
            case KeyEvent.KEYCODE_DPAD_LEFT: return arrow(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return arrow(1, 0);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) {
                    e.startTracking();
                    longPressed = false;
                    press(true);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                // Something is playing: let the system hand the key to its media session.
                if (view.overlay.nowPlaying.active()) return super.onKeyDown(code, e);
                // fall through
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                if (e.getRepeatCount() == 0 && !view.moveMode) openMenu();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true; // handled on key up, like the system does
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            longPressed = true;
            press(false);
            if (!view.moveMode && !view.overlay.menuOpen()) openMenu();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            press(false);
            if (longPressed || e.isCanceled()) { longPressed = false; return true; }
            if (view.overlay.menuOpen()) choose(view.overlay.menuChoice());
            else if (view.moveMode) { endMove(); sounds.play(Sounds.SELECT); }
            else launch();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (view.overlay.menuOpen()) { view.overlay.closeMenu(); sounds.play(Sounds.BACK); }
            else if (view.moveMode) { endMove(); sounds.play(Sounds.BACK); }
            else if (view.focus >= HomeView.COLS) { view.scrollToTop(); sounds.play(Sounds.BACK); }
            return true; // this is home: Back never leaves it
        }
        return super.onKeyUp(code, e);
    }

    private boolean arrow(int dx, int dy) {
        boolean moved;
        if (view.overlay.menuOpen()) moved = dy != 0 && view.overlay.menuStep(dy);
        else moved = view.step(dx, dy);
        sounds.play(moved ? Sounds.MOVE : Sounds.EDGE);
        return true;
    }

    /** Feedback on press, not release: the tile sinks a little while select is held. */
    private void press(boolean down) {
        if (view.overlay.menuOpen() || view.focused() == null) return;
        HomeView.Tile t = view.tiles.get(view.focused());
        if (t == null) return;
        t.lift.to(down ? 0.55f : 1f);
        view.kick();
    }

    // ---- actions ---------------------------------------------------------------

    private void launch() {
        Apps.App a = view.focused();
        if (a == null) return;
        sounds.play(Sounds.LAUNCH);
        Rect from = view.focusedRect();
        ActivityOptions zoom = ActivityOptions.makeScaleUpAnimation(view, from.left, from.top, from.width(), from.height());
        try {
            startActivity(a.launchIntent(), zoom.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "launch failed " + a.key, e);
            view.overlay.toast("“" + a.label + "” can’t be opened");
        }
    }

    private void openMenu() {
        Apps.App a = view.focused();
        if (a == null) return;
        List<String> o = new ArrayList<>();
        o.add(OPEN);
        o.add(MOVE);
        o.add(a.hidden ? UNHIDE : HIDE);
        o.add(INFO);
        boolean anyHidden = false;
        for (Apps.App x : all) anyHidden |= x.hidden;
        if (view.showHidden) o.add(HIDE_HIDDEN);
        else if (anyHidden) o.add(SHOW_HIDDEN);
        o.add(sounds.isEnabled() ? SOUND_OFF : SOUND_ON);
        sounds.play(Sounds.EDIT);
        view.overlay.openMenu(o.toArray(new String[0]));
    }

    private void choose(String choice) {
        Apps.App a = view.focused();
        view.overlay.closeMenu();
        if (choice == null || a == null) return;
        sounds.play(Sounds.SELECT);
        switch (choice) {
            case OPEN: launch(); break;
            case MOVE: view.moveMode = true; view.kick(); break;
            case HIDE:
                a.hidden = true;
                apps.saveOrder(all);
                view.setApps(all, null);
                view.overlay.toast("“" + a.label + "” hidden  ·  Hold select → Show Hidden Apps to bring it back");
                break;
            case UNHIDE: a.hidden = false; apps.saveOrder(all); view.setApps(all, a.key); break;
            case INFO:
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", a.component.getPackageName(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case SHOW_HIDDEN: view.showHidden = true; view.setApps(all, a.key); break;
            case HIDE_HIDDEN: view.showHidden = false; view.setApps(all, a.key); break;
            case SOUND_OFF:
            case SOUND_ON:
                sounds.setEnabled(choice.equals(SOUND_ON));
                prefs.edit().putBoolean("sounds", sounds.isEnabled()).apply();
                if (sounds.isEnabled()) sounds.play(Sounds.SELECT);
                break;
            default: break;
        }
    }

    /** Ends move mode and writes the new order, merging hidden apps back in place. */
    private void endMove() {
        if (!view.moveMode) return;
        view.moveMode = false;
        List<Apps.App> merged = new ArrayList<>(view.visible);
        for (int i = 0; i < all.size(); i++) {
            Apps.App x = all.get(i);
            if (!merged.contains(x)) merged.add(Math.min(i, merged.size()), x);
        }
        all = merged;
        apps.saveOrder(all);
        view.kick();
    }
}
