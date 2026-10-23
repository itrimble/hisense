package com.ian.podcaststv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.webkit.WebView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * On launch, offers the episode the viewer was last on: a card at the bottom of the
 * screen with Resume and Not Now. Left/right choose, OK presses, Back dismisses; it
 * goes away by itself after a while.
 */
final class ResumeCard {
    private static final long SHOW_MS = 20_000;
    private static final long MAX_AGE_MS = 30L * 24 * 3600_000; // a month
    private static final int WRAP = FrameLayout.LayoutParams.WRAP_CONTENT;

    private static final long PLAY_POLL_MS = 700;
    private static final int PLAY_TRIES = 60;
    private int resumeRequest;

    private final Activity activity;
    private final FrameLayout root;
    private final WebView web;
    private final float d;
    private LinearLayout card;
    private TextView[] buttons;
    private int chosen;
    private JSONObject episode;
    private final Runnable timeout = this::hide;

    ResumeCard(Activity activity, FrameLayout root, WebView web) {
        this.activity = activity;
        this.root = root;
        this.web = web;
        this.d = activity.getResources().getDisplayMetrics().density;
    }

    /** Worth offering: recent, started, and not nearly finished. */
    static boolean worthOffering(JSONObject e) {
        if (e == null || !MainActivity.apple(e.optString("url"))) return false; // only Apple's own pages
        double t = e.optDouble("position", 0), dur = e.optDouble("duration", 0);
        long age = System.currentTimeMillis() - e.optLong("when", 0);
        return t > 30 && (dur <= 0 || dur - t > 60) && age < MAX_AGE_MS;
    }

    boolean isShowing() { return card != null; }

    void show(JSONObject e) {
        hide();
        episode = e;
        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (20 * d);
        card.setPadding(pad, pad, (int) (28 * d), pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF202022);
        bg.setCornerRadius(16 * d);
        card.setBackground(bg);
        card.setElevation(12 * d);

        ImageView art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int px = (int) (112 * d);
        card.addView(art, new LinearLayout.LayoutParams(px, px));
        Artwork.load(e.optString("art"), b -> { if (b != null) art.setImageBitmap(b); });

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding((int) (20 * d), 0, 0, 0);
        text.addView(label("Continue " + (e.optBoolean("video") ? "watching" : "listening"), 13, false, 0.6f));
        text.addView(label(e.optString("title"), 19, true, 1f));
        double left = e.optDouble("duration", 0) - e.optDouble("position", 0);
        String sub = e.optString("show") + (left > 0 ? "  ·  " + Math.max(1, Math.round(left / 60)) + " min left" : "");
        text.addView(label(sub, 13, false, 0.6f));
        LinearLayout row = new LinearLayout(activity);
        row.setPadding(0, (int) (12 * d), 0, 0);
        buttons = new TextView[]{button("Resume"), button("Not Now")};
        for (TextView b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.rightMargin = (int) (12 * d);
            row.addView(b, lp);
        }
        text.addView(row);
        card.addView(text, new LinearLayout.LayoutParams((int) (440 * d), WRAP));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = (int) (48 * d);
        lp.bottomMargin = (int) (48 * d);
        root.addView(card, lp);
        chosen = 0;
        paint();
        card.setAlpha(0f);
        card.setTranslationY(24 * d);
        card.animate().alpha(1f).translationY(0).setDuration(350);
        card.postDelayed(timeout, SHOW_MS);
    }

    void hide() {
        if (card == null) return;
        card.removeCallbacks(timeout);
        root.removeView(card);
        card = null;
    }

    /** Remote keys while the card is up; returns true if consumed. */
    boolean key(KeyEvent e) {
        if (card == null) return false;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!up) { chosen = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1; paint(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (up) {
                    boolean resume = chosen == 0 || e.getKeyCode() != KeyEvent.KEYCODE_DPAD_CENTER
                            && e.getKeyCode() != KeyEvent.KEYCODE_ENTER;
                    JSONObject ep = episode;
                    hide();
                    if (resume) resume(ep);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (up) hide();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
        }
        hide(); // any other key: the viewer is doing something else
        return false;
    }

    /** Opens the episode's page and presses its Play button there (Apple resumes where it left off). */
    void cancelResume() { resumeRequest++; }

    void resume(JSONObject e) {
        final int request = ++resumeRequest;
        String id = e.optString("id").replaceAll("\\D", "");
        double t = e.optDouble("position", 0);
        android.util.Log.i(MainActivity.TAG, "Resume requested for episode " + id + " at " + t);
        web.loadUrl(e.optString("url"));
        web.postDelayed(new Runnable() {
            int tries;
            @Override public void run() {
                if (request != resumeRequest) return;
                if (++tries > PLAY_TRIES) { android.util.Log.w(MainActivity.TAG, "Resume timed out"); return; }
                Runnable again = this;
                web.evaluateJavascript("window.__ptv && __ptv.playEpisodePage ? __ptv.playEpisodePage('" + id + "', " + t + ") : 'wait'",
                        r -> {
                            if (tries == 1 || tries % 10 == 0 || "\"ok\"".equals(r))
                                android.util.Log.i(MainActivity.TAG, "Resume poll " + tries + ": " + r);
                            if (request == resumeRequest && !"\"ok\"".equals(r)) web.postDelayed(again, PLAY_POLL_MS);
                        });
            }
        }, PLAY_POLL_MS);
    }

    private void paint() {
        for (int i = 0; i < buttons.length; i++) {
            GradientDrawable g = (GradientDrawable) buttons[i].getBackground();
            boolean on = i == chosen;
            g.setColor(on ? Color.WHITE : 0x33FFFFFF);
            buttons[i].setTextColor(on ? Color.BLACK : Color.WHITE);
        }
    }

    private TextView button(String s) {
        TextView t = label(s, 15, true, 1f);
        t.setPadding((int) (22 * d), (int) (8 * d), (int) (22 * d), (int) (8 * d));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(20 * d);
        t.setBackground(g);
        return t;
    }

    private TextView label(String s, int sp, boolean bold, float alpha) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setAlpha(alpha);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
}
