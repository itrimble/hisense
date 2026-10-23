package com.ian.tvhome;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Interface sounds. The built-in set is synthesised by make_assets.py. Any file named
 * move/select/back/edge/edit/launch.wav (or .ogg) dropped into the app's external
 * files "sounds" folder replaces the built-in one:
 *   /sdcard/Android/data/com.ian.tvhome/files/sounds/
 */
final class Sounds {
    static final String MOVE = "move", SELECT = "select", BACK = "back", EDGE = "edge", EDIT = "edit", LAUNCH = "launch";
    private static final String[] ALL = {MOVE, SELECT, BACK, EDGE, EDIT, LAUNCH};

    private final SoundPool pool;
    private final Map<String, Integer> ids = new HashMap<>();
    private boolean enabled = true;

    Sounds(Context c) {
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        File dir = c.getExternalFilesDir("sounds");
        for (String name : ALL) {
            File custom = findCustom(dir, name);
            try {
                if (custom != null) {
                    ids.put(name, pool.load(custom.getPath(), 1));
                } else {
                    AssetFileDescriptor fd = c.getAssets().openFd("sounds/" + name + ".wav");
                    ids.put(name, pool.load(fd, 1));
                }
            } catch (IOException e) {
                Log.w(Home.TAG, "sound " + name + " unavailable", e);
            }
        }
    }

    private static File findCustom(File dir, String name) {
        if (dir == null) return null;
        for (String ext : new String[]{".wav", ".ogg"}) {
            File f = new File(dir, name + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    void setEnabled(boolean on) { enabled = on; }

    boolean isEnabled() { return enabled; }

    void play(String name) {
        Integer id = ids.get(name);
        if (enabled && id != null) pool.play(id, 1f, 1f, 1, 0, 1f);
    }

    void release() { pool.release(); }
}
