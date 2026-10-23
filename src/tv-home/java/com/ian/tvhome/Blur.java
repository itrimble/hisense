package com.ian.tvhome;

import android.graphics.Bitmap;

/**
 * Soft colour fields for the ambient background. Android 11 has no RenderEffect blur,
 * so artwork is shrunk to a few dozen pixels and box-blurred; drawn back up to full
 * screen with bilinear filtering it reads as a deep, smooth blur that costs nothing per frame.
 */
final class Blur {
    private Blur() { }

    static Bitmap tiny(Bitmap src, int w, int h) {
        if (src == null) return null;
        Bitmap s = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            px = boxH(px, w, h, 3);
            px = boxV(px, w, h, 3);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static int[] boxH(int[] in, int w, int h, int r) {
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int c = in[y * w + Math.max(0, Math.min(w - 1, x + k))];
                    a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                out[y * w + x] = (a / n) << 24 | (rr / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
        return out;
    }

    private static int[] boxV(int[] in, int w, int h, int r) {
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int c = in[Math.max(0, Math.min(h - 1, y + k)) * w + x];
                    a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                out[y * w + x] = (a / n) << 24 | (rr / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
        return out;
    }
}
