#!/usr/bin/env python3
"""Generate the launcher's own sounds and icon art (all original, synthesised here).

    python3 make_assets.py      -> assets/sounds/*.wav, res/mipmap-*/ic_launcher.png, res/drawable-xhdpi/banner.png
"""
import os
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
RATE = 44100


def t(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def env(x, tau, attack=0.001):
    e = np.exp(-x / tau)
    a = np.clip(x / attack, 0, 1)
    return e * a


def sweep(x, f0, f1, dur):
    k = np.clip(x / dur, 0, 1)
    f = f0 + (f1 - f0) * (1 - (1 - k) ** 2)
    return np.sin(2 * np.pi * np.cumsum(f) / RATE)


def lowpass(sig, cutoff):
    a = np.exp(-2 * np.pi * cutoff / RATE)
    out = np.zeros_like(sig)
    acc = 0.0
    for i, s in enumerate(sig):
        acc = (1 - a) * s + a * acc
        out[i] = acc
    return out


def save(name, sig, peak_db):
    sig = sig / (np.max(np.abs(sig)) + 1e-9) * 10 ** (peak_db / 20)
    fade = int(RATE * 0.004)
    sig[-fade:] *= np.linspace(1, 0, fade)
    data = (sig * 32767).astype('<i2').tobytes()
    path = os.path.join(HERE, 'assets', 'sounds', name + '.wav')
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data)


def sounds():
    rng = np.random.default_rng(7)
    os.makedirs(os.path.join(HERE, 'assets', 'sounds'), exist_ok=True)

    # move: a soft wooden "tock" for every focus step
    x = t(0.05)
    tick = (np.sin(2 * np.pi * 1850 * x) * env(x, 0.005)
            + 0.6 * np.sin(2 * np.pi * 925 * x) * env(x, 0.009)
            + 0.25 * lowpass(rng.standard_normal(len(x)), 4000) * env(x, 0.0012))
    save('move', tick, -15)

    # select: a rounded upward pop
    x = t(0.13)
    pop = (sweep(x, 520, 900, 0.03) * env(x, 0.028, 0.002)
           + 0.3 * np.sin(2 * np.pi * 1800 * x) * env(x, 0.006))
    save('select', pop, -10)

    # back: the same pop falling away
    x = t(0.12)
    save('back', sweep(x, 900, 480, 0.05) * env(x, 0.03, 0.002), -12)

    # edge: a muted thud when there is nowhere further to go
    x = t(0.08)
    thud = np.sin(2 * np.pi * 170 * x) * env(x, 0.018) + 0.4 * np.sin(2 * np.pi * 340 * x) * env(x, 0.01)
    save('edge', thud, -11)

    # edit: two soft bell notes when the home screen enters edit mode
    x = t(0.42)
    bell = np.zeros_like(x)
    for start, f in ((0.0, 1318.5), (0.09, 1975.5)):
        y = np.clip(x - start, 0, None)
        on = (x >= start).astype(float)
        bell += on * (np.sin(2 * np.pi * f * y) + 0.3 * np.sin(2 * np.pi * f * 2.76 * y)) * env(y, 0.09)
    save('edit', bell, -14)

    # launch: an airy rising swell as an app opens
    x = t(0.28)
    air = lowpass(rng.standard_normal(len(x)), 2500) * np.sin(np.pi * np.clip(x / 0.28, 0, 1)) ** 2
    save('launch', air + 0.5 * sweep(x, 300, 1100, 0.25) * np.sin(np.pi * x / 0.28) ** 2, -16)


def tile_glyph(img, box):
    d = ImageDraw.Draw(img)
    x0, y0, x1, y1 = box
    cols, rows, gap = 3, 2, (x1 - x0) * 0.08
    w = ((x1 - x0) - gap * (cols - 1)) / cols
    h = ((y1 - y0) - gap * (rows - 1)) / rows
    for r in range(rows):
        for c in range(cols):
            a = x0 + c * (w + gap)
            b = y0 + r * (h + gap)
            fill = (255, 255, 255, 255 if (r, c) == (0, 0) else 150)
            d.rounded_rectangle([a, b, a + w, b + h], radius=h * 0.18, fill=fill)


def backdrop(w, h):
    yy, xx = np.mgrid[0:h, 0:w]
    g = (xx / w * 0.6 + yy / h * 0.4)
    top = np.array([58, 58, 66])
    bottom = np.array([14, 14, 18])
    rgb = top * (1 - g[..., None]) + bottom * g[..., None]
    return Image.fromarray(rgb.astype('uint8'), 'RGB').convert('RGBA')


def art():
    banner = backdrop(320, 180)
    tile_glyph(banner, (100, 52, 220, 128))
    banner = banner.filter(ImageFilter.SMOOTH)
    os.makedirs(os.path.join(HERE, 'res', 'drawable-xhdpi'), exist_ok=True)
    banner.convert('RGB').save(os.path.join(HERE, 'res', 'drawable-xhdpi', 'banner.png'))

    for folder, size in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
        big = backdrop(size * 4, size * 4)
        tile_glyph(big, (size * 0.9, size * 1.25, size * 3.1, size * 2.75))
        mask = Image.new('L', big.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=size * 0.9, fill=255)
        big.putalpha(mask)
        out = os.path.join(HERE, 'res', 'mipmap-' + folder)
        os.makedirs(out, exist_ok=True)
        big.resize((size, size), Image.LANCZOS).save(os.path.join(out, 'ic_launcher.png'))


if __name__ == '__main__':
    sounds()
    art()
