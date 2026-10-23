"""Draw the launcher icon and the 320x180 Android TV / Projectivy banner.

Run: python3 make_art.py   (needs Pillow; writes into res/)
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
TOP = (214, 112, 251)     # podcast purple, light
BOTTOM = (132, 43, 226)   # podcast purple, deep
SS = 4                    # supersample factor


def gradient(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM))
        for x in range(w):
            px[x, y] = c
    return img


def glyph(size):
    """White broadcast glyph: head, stem, two arcs. Returns an RGBA square."""
    s = size * SS
    g = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    cx, cy = s / 2, s * 0.44
    for r, wdt in ((0.40, 0.065), (0.27, 0.065)):
        R = s * r
        d.arc([cx - R, cy - R, cx + R, cy + R], start=135, end=405, fill="white", width=round(s * wdt))
    head = s * 0.095
    d.ellipse([cx - head, cy - head, cx + head, cy + head], fill="white")
    stem_w = s * 0.085
    d.rounded_rectangle([cx - stem_w / 2, cy + head * 1.35, cx + stem_w / 2, s * 0.86],
                        radius=stem_w / 2, fill="white")
    return g.resize((size, size), Image.LANCZOS)


def icon(px):
    img = gradient(px, px).convert("RGBA")
    mask = Image.new("L", (px * SS, px * SS), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, px * SS - 1, px * SS - 1], radius=px * SS * 0.22, fill=255)
    img.putalpha(mask.resize((px, px), Image.LANCZOS))
    gs = round(px * 0.78)
    img.alpha_composite(glyph(gs), ((px - gs) // 2, (px - gs) // 2))
    return img


def font(size):
    for path in ("/System/Library/Fonts/SFNS.ttf", "/System/Library/Fonts/HelveticaNeue.ttc",
                 "/System/Library/Fonts/Helvetica.ttc"):
        if os.path.exists(path):
            try:
                f = ImageFont.truetype(path, size)
                try:
                    f.set_variation_by_name("Semibold")
                except Exception:
                    pass
                return f
            except OSError:
                continue
    return ImageFont.load_default()


def banner(w=320, h=180):
    img = gradient(w, h).convert("RGBA")
    gs = 112
    shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    g = glyph(gs)
    shadow.paste((0, 0, 0, 70), (22, (h - gs) // 2 + 3), g)
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(4)))
    img.alpha_composite(g, (22, (h - gs) // 2))
    d = ImageDraw.Draw(img)
    d.text((142, h / 2), "Podcasts", font=font(38), fill="white", anchor="lm")
    return img.convert("RGB")


def save(img, rel):
    path = os.path.join(HERE, "res", rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", os.path.relpath(path, HERE), img.size)


if __name__ == "__main__":
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(icon(px), f"mipmap-{density}/ic_launcher.png")
    save(banner(), "drawable-xhdpi/banner.png")
