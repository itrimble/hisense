package com.ian.tvhome;

import android.content.Context;
import android.graphics.Typeface;

import java.io.File;

/**
 * Typefaces. Inter (SIL Open Font License, assets/fonts/Inter-OFL.txt) is built in.
 * A font you are licensed to use on this device can replace each face by dropping a
 * file with the same role name into the app's external files "fonts" folder:
 *   /sdcard/Android/data/com.ian.tvhome/files/fonts/{Text,TextMedium,TextSemibold,Display,DisplaySemibold}.ttf|.otf
 */
final class Fonts {
    final Typeface text, medium, semibold, display, displaySemibold;

    Fonts(Context c) {
        File dir = c.getExternalFilesDir("fonts");
        text = load(c, dir, "Text", "Inter-Regular.ttf");
        medium = load(c, dir, "TextMedium", "Inter-Medium.ttf");
        semibold = load(c, dir, "TextSemibold", "Inter-SemiBold.ttf");
        display = load(c, dir, "Display", "InterDisplay-Bold.ttf");
        displaySemibold = load(c, dir, "DisplaySemibold", "InterDisplay-SemiBold.ttf");
    }

    private static Typeface load(Context c, File dir, String role, String asset) {
        if (dir != null) {
            for (String ext : new String[]{".ttf", ".otf"}) {
                File f = new File(dir, role + ext);
                if (f.isFile()) {
                    try { return Typeface.createFromFile(f); } catch (RuntimeException ignored) { }
                }
            }
        }
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/" + asset);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }
}
