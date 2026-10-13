"""Render SloopyTV launcher assets (design C: wordmark, amber TV, HANG ON, colour bars)."""
import sys, os
from PIL import Image, ImageDraw, ImageFont

FONTS = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)

GROUND = (11, 11, 12, 255)
TEXT = (235, 233, 228, 255)
AMBER = (232, 165, 58, 255)
MUTED = (141, 138, 132, 255)
BARS = [(192, 192, 192), (192, 192, 0), (0, 192, 192), (0, 192, 0), (192, 0, 192), (192, 0, 0), (0, 0, 192)]

def font(name, size, weight):
    f = ImageFont.truetype(os.path.join(FONTS, name), size)
    try:
        f.set_variation_by_axes([weight])
    except Exception as e:
        print("variation not applied:", e)
    return f

def draw_bars(d, x0, y0, x1, y1):
    n = len(BARS)
    w = (x1 - x0) / n
    for i, c in enumerate(BARS):
        d.rectangle([x0 + i * w, y0, x0 + (i + 1) * w, y1], fill=c + (255,))

def text_w(d, s, f, spacing=0):
    return d.textlength(s, font=f) + spacing * max(0, len(s) - 1)

def draw_spaced(d, xy, s, f, fill, spacing):
    x, y = xy
    for ch in s:
        d.text((x, y), ch, font=f, fill=fill)
        x += d.textlength(ch, font=f) + spacing

# ---- Banner: design at 1280x720 (4x the 320x180 dp banner), downscaled later ---------
def banner():
    W, H = 1280, 720
    im = Image.new("RGBA", (W, H), GROUND)
    d = ImageDraw.Draw(im)
    big = font("big_shoulders_display.ttf", 300, 800)
    mono = font("geist_mono.ttf", 48, 500)
    # SLOOPY, top-left, cap height sits at ~y=120
    d.text((88, 100), "SLOOPY", font=big, fill=TEXT, anchor="la")
    # TV in amber, bottom-right of the wordmark
    tvw = d.textlength("TV", font=big)
    d.text((W - 88 - tvw, 318), "TV", font=big, fill=AMBER, anchor="la")
    # HANG ON, small mono, letterspaced
    draw_spaced(d, (96, 578), "HANG ON", mono, MUTED, 12)
    # colour bars along the bottom
    draw_bars(d, 0, H - 44, W, H)
    return im

# ---- Legacy square icon: full bleed, bars at the bottom -------------------------------
def icon_full(px):
    im = Image.new("RGBA", (px, px), GROUND)
    d = ImageDraw.Draw(im)
    big = font("big_shoulders_display.ttf", int(px * 0.80), 800)
    sw = d.textlength("S", font=big)
    d.text(((px - sw) / 2, px * 0.08), "S", font=big, fill=TEXT, anchor="la")
    draw_bars(d, 0, px - px * 0.065, px, px)
    return im

# ---- Adaptive foreground: content inside the central safe zone (66 of 108 dp) ----------
def icon_fg(px):
    im = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    inner = px * 0.62
    x0 = (px - inner) / 2
    y0 = (px - inner) / 2
    big = font("big_shoulders_display.ttf", int(inner * 0.92), 800)
    sw = d.textlength("S", font=big)
    d.text(((px - sw) / 2, y0 + inner * 0.0), "S", font=big, fill=TEXT, anchor="la")
    draw_bars(d, x0, y0 + inner - inner * 0.075, x0 + inner, y0 + inner)
    return im

def save(im, path, size=None):
    if size:
        im = im.resize(size, Image.LANCZOS)
    im.save(path, "PNG", optimize=True)
    print(path, im.size)

b = banner()
save(b, os.path.join(OUT, "banner_xhdpi.png"), (640, 360))
save(b, os.path.join(OUT, "banner_xxhdpi.png"), (960, 540))
save(b, os.path.join(OUT, "banner_preview.png"), (1280, 720))

full = icon_full(768)
for name, s in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    save(full, os.path.join(OUT, f"ic_launcher_{name}.png"), (s, s))

fg = icon_fg(864)
for name, s in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]:
    save(fg, os.path.join(OUT, f"ic_launcher_foreground_{name}.png"), (s, s))
