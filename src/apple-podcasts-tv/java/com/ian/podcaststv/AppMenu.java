package com.ian.podcaststv;

import android.app.AlertDialog;

/** The app's own menu (Info key, or hold OK): playback, chapters, phone typing and display. */
final class AppMenu {
    private AppMenu() { }

    static void show(MainActivity a) {
        int w = a.prefs.getInt("width", MainActivity.DEFAULT_WIDTH);
        String[] items = {"Play / Pause", "Back 15 s", "Forward 30 s",
                "Playback speed (" + Speed.label(a.playback.speed()) + ")",
                "Sleep timer (" + a.playback.sleepStatus() + ")",
                a.playback.hasChapters() ? "Chapters" : "Chapters (none for this episode)", "Watch video full screen",
                "Type with your phone (QR code)", "Home", "Reload", "Larger text", "Smaller text",
                a.pointerMode() ? "Arrows move between buttons" : "Arrows move a pointer",
                a.sharp() ? "Smoother scrolling (softer text)" : "Sharper text (slower scrolling)",
                "Quit (stops playback)", "Manage private feeds", "Library"};
        new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Podcasts")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: a.media("toggle"); break;
                        case 1: a.media("back"); break;
                        case 2: a.media("fwd"); break;
                        case 3: a.playback.showSpeedMenu(); break;
                        case 4: a.playback.showSleepMenu(); break;
                        case 5: a.playback.showChaptersMenu(); break;
                        case 6: a.playback.startVideo(); break;
                        case 7: a.togglePhoneKeyboard(); break;
                        case 8: a.openApple(MainActivity.HOME, false); break;
                        case 9: if(a.web != null) a.web.reload(); else a.showLibrary(); break;
                        case 10: a.setWidthIndex(w - 1); break;
                        case 11: a.setWidthIndex(w + 1); break;
                        case 12: a.setPointerMode(!a.pointerMode()); break;
                        case 13: a.prefs.edit().putBoolean("sharp", !a.sharp()).apply(); a.applyRenderScale(); break;
                        case 14: a.quit(); break;
                        case 15: a.privateFeeds.show(); break;
                        case 16: a.showLibrary(); break;
                    }
                })
                .show();
    }
}
