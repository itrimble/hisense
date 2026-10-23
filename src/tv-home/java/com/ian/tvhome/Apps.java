package com.ian.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installed apps, their artwork, and the order and visibility the viewer chose. */
final class Apps {
    static final class App {
        final String key, label;
        final ComponentName component;
        final boolean leanback;
        Bitmap tile, hero, ambient;
        int color;
        float luma; // 0..1, how bright the artwork is overall
        boolean hidden;

        App(ComponentName c, String label, boolean leanback) {
            this.component = c;
            this.key = c.flattenToShortString();
            this.label = label;
            this.leanback = leanback;
        }

        Intent launchIntent() {
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
    }

    /** Apps people reach for first on a fresh home screen; everything else follows alphabetically. */
    private static final List<String> FIRST = Arrays.asList(
            "com.apple.atve.androidtv.appletv", "com.ian.podcaststv", "com.apple.android.music",
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.wbd.stream", "com.disney.disneyplus", "com.plexapp.android", "com.android.tv.settings");

    private final Context context;
    private final SharedPreferences prefs;
    private final int tileW, tileH, heroW, heroH;

    Apps(Context c, int tileW, int tileH, int heroW, int heroH) {
        context = c;
        prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        this.tileW = tileW;
        this.tileH = tileH;
        this.heroW = heroW;
        this.heroH = heroH;
    }

    /** Slow: queries the package manager and renders artwork. Call off the UI thread. */
    List<App> load() {
        PackageManager pm = context.getPackageManager();
        Map<String, App> found = new LinkedHashMap<>();
        Set<String> packages = new HashSet<>();
        for (boolean leanback : new boolean[]{true, false}) {
            Intent q = new Intent(Intent.ACTION_MAIN)
                    .addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai.packageName.equals(context.getPackageName())) continue;
                if (!leanback && packages.contains(ai.packageName)) continue; // TV entry wins
                ComponentName cn = new ComponentName(ai.packageName, ai.name);
                App a = new App(cn, String.valueOf(ri.loadLabel(pm)).trim(), leanback);
                found.put(a.key, a);
                packages.add(ai.packageName);
            }
        }
        Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
        List<App> out = new ArrayList<>(order(found, hidden));
        for (App a : out) render(pm, a);
        return out;
    }

    private List<App> order(Map<String, App> found, Set<String> hidden) {
        List<App> out = new ArrayList<>();
        String saved = prefs.getString("order", "");
        if (!saved.isEmpty()) {
            for (String k : saved.split("\n")) {
                App a = found.remove(k);
                if (a != null) out.add(a);
            }
        }
        List<App> rest = new ArrayList<>(found.values());
        Collections.sort(rest, (x, y) -> {
            int px = FIRST.indexOf(x.component.getPackageName()), py = FIRST.indexOf(y.component.getPackageName());
            if (px < 0) px = Integer.MAX_VALUE;
            if (py < 0) py = Integer.MAX_VALUE;
            return px != py ? Integer.compare(px, py) : x.label.compareToIgnoreCase(y.label);
        });
        out.addAll(rest);
        for (App a : out) a.hidden = hidden.contains(a.key);
        return out;
    }

    void saveOrder(List<App> apps) {
        StringBuilder sb = new StringBuilder();
        Set<String> hidden = new HashSet<>();
        for (App a : apps) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(a.key);
            if (a.hidden) hidden.add(a.key);
        }
        prefs.edit().putString("order", sb.toString()).putStringSet("hidden", hidden).apply();
    }

    // ---- artwork ------------------------------------------------------------

    private void render(PackageManager pm, App a) {
        ActivityInfo ai;
        try {
            ai = pm.getActivityInfo(a.component, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        Drawable banner = a.leanback ? bannerAt(pm, ai, DisplayMetrics.DENSITY_XXXHIGH) : null;
        if (banner == null && a.leanback) banner = ai.loadBanner(pm);
        Drawable icon = banner == null ? iconAt(pm, ai) : null;

        if (banner != null) {
            a.tile = cover(banner, tileW, tileH);
            a.hero = cover(banner, heroW, heroH);
            a.color = average(a.tile);
        } else {
            Bitmap probe = draw(icon, 48, 48);
            a.color = average(probe);
            a.tile = iconCard(icon, a.color, tileW, tileH);
            a.hero = iconCard(icon, a.color, heroW, heroH);
        }
        a.ambient = Blur.tiny(a.hero, 64, 36);
        a.luma = (0.2126f * Color.red(a.color) + 0.7152f * Color.green(a.color) + 0.0722f * Color.blue(a.color)) / 255f;
    }

    private static Drawable bannerAt(PackageManager pm, ActivityInfo ai, int density) {
        int res = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
        if (res == 0) return null;
        try {
            Resources r = pm.getResourcesForApplication(ai.applicationInfo);
            return r.getDrawableForDensity(res, density, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Drawable iconAt(PackageManager pm, ActivityInfo ai) {
        int res = ai.getIconResource();
        if (res != 0) {
            try {
                Drawable d = pm.getResourcesForApplication(ai.applicationInfo)
                        .getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            } catch (Exception ignored) { }
        }
        return ai.loadIcon(pm);
    }

    /** Scales a drawable to fill w x h, cropping the overflow (like centerCrop). */
    private static Bitmap cover(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.rgb(28, 28, 30));
        int iw = Math.max(1, d.getIntrinsicWidth()), ih = Math.max(1, d.getIntrinsicHeight());
        if (d.getIntrinsicWidth() <= 0) { iw = w; ih = h; }
        float s = Math.max(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * s), dh = Math.round(ih * s);
        d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
        d.setFilterBitmap(true);
        d.draw(c);
        return b;
    }

    private static Bitmap draw(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (d != null) {
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(b));
        }
        return b;
    }

    /** A phone-style icon centred on a tile tinted from the icon's own colour. */
    private static Bitmap iconCard(Drawable icon, int color, int w, int h) {
        if (icon instanceof AdaptiveIconDrawable) return layeredCard((AdaptiveIconDrawable) icon, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], 0.55f);
        hsv[2] = 0.42f;
        int top = Color.HSVToColor(hsv);
        hsv[2] = 0.22f;
        int bottom = Color.HSVToColor(hsv);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        if (icon != null) {
            int s = Math.round(h * 0.56f);
            Bitmap ib = draw(icon, s, s);
            Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            ip.setShadowLayer(h * 0.04f, 0, h * 0.015f, 0x66000000);
            c.drawBitmap(ib, null, new RectF((w - s) / 2f, (h - s) / 2f, (w + s) / 2f, (h + s) / 2f), ip);
        }
        return b;
    }

    /**
     * A layered (adaptive) icon as a tvOS-style tile: its background layer fills the
     * whole tile and its foreground symbol sits centred on top, the way Apple TV draws
     * app icons. The layers are 108 units square with the symbol inside the middle 72.
     */
    private static Bitmap layeredCard(AdaptiveIconDrawable icon, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable bg = icon.getBackground(), fg = icon.getForeground();
        if (bg != null) {
            int side = Math.max(w, h);
            bg.setBounds((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
            bg.draw(c);
        } else {
            c.drawColor(Color.rgb(28, 28, 30));
        }
        if (fg != null) {
            int s = Math.round(h * 0.62f * 108f / 72f); // the symbol's safe zone fills ~62% of the height
            fg.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
            fg.draw(c);
        }
        return b;
    }

    private static int average(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
        long r = 0, g = 0, bl = 0, n = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int c = s.getPixel(x, y);
                if (Color.alpha(c) < 128) continue;
                r += Color.red(c);
                g += Color.green(c);
                bl += Color.blue(c);
                n++;
            }
        }
        if (n == 0) return Color.rgb(60, 60, 66);
        return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

}
