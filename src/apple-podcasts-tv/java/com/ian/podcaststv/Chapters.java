package com.ian.podcaststv;

import android.app.Activity;
import android.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Episode chapters as Apple lists them (fetched by nowplaying.js), and a picker. */
final class Chapters {
    static final class Chapter {
        final String name;
        final double start;
        Chapter(String name, double start) { this.name = name; this.start = start; }
    }

    private Chapters() { }

    /** [{name, start}] in seconds, sorted. */
    static List<Chapter> parse(String json) {
        List<Chapter> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Chapter(o.optString("name"), o.optDouble("start", 0)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** The chapter playing at `sec`, or null. */
    static Chapter at(List<Chapter> list, double sec) {
        Chapter cur = null;
        for (Chapter c : list) if (c.start <= sec + 0.5) cur = c;
        return cur;
    }

    /** Lists the chapters with their start times; picking one seeks there. */
    static void showMenu(Activity activity, List<Chapter> list, double position, Consumer<Double> seek) {
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) items[i] = clock(list.get(i).start) + "    " + list.get(i).name;
        new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Chapters")
                .setSingleChoiceItems(items, list.indexOf(at(list, position)), (d, which) -> {
                    seek.accept(list.get(which).start);
                    d.dismiss();
                })
                .show();
    }

    static String clock(double sec) {
        int s = (int) Math.max(0, sec), h = s / 3600, m = (s / 60) % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.US, "%d:%02d", m, s % 60);
    }
}
