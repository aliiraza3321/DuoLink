"""
Generates the DuoLink installer artwork.

The installer now carries the same minimal dark identity as the app: one flat
background, one hairline accent, the product mark, a single filled action pill
and nothing else. No gradients, no patterns, no decorative illustration.

NSIS cannot round or recolour a native button into that pill, so the page is
rendered here as one bitmap and the NSIS page only places invisible click
targets over the pill and the text link. That keeps the artwork pixel-exact on
every Windows version and every DPI.

Run from desktop/:  python build/make-installer-art.py
"""

import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(HERE), "renderer", "assets")

W, H = 490, 238          # NSIS assistant page area under the branded header
SS = 4                   # supersampling factor

# Cream "honeycomb" identity: warm paper with a faint hex grid, ink type and
# one dark-gold action pill. Every MUI page is painted with the same cream, so
# the wizard never falls back to a black window between the branded pages.
BG = (243, 238, 226)      # cream paper
HONEY = (231, 222, 204)   # hex grid lines
INK = (30, 27, 22)        # headings
BODY = (95, 90, 78)       # body copy
MUTED = (140, 132, 114)   # fine print
ACCENT = (140, 102, 32)   # dark gold pill
LINE = (222, 214, 198)
DANGER = (168, 60, 48)

FONTS = r"C:\Windows\Fonts"


def font(name, size):
    try:
        return ImageFont.truetype(os.path.join(FONTS, name), size)
    except Exception:
        return ImageFont.load_default()


def honeycomb(img, scale):
    """Faint hex grid, the page's only decoration."""
    d = ImageDraw.Draw(img)
    r = 26 * scale
    dx = 1.5 * r
    dy = (3 ** 0.5) / 2 * r
    cols = int(W * scale / dx) + 3
    rows = int(H * scale / dy) + 3
    for row in range(rows):
        for col in range(cols):
            cx = col * dx
            cy = row * dy * 2 + (dy if col % 2 else 0)
            pts = []
            for i in range(6):
                ang = (60 * i - 30) * 3.14159265 / 180
                pts.append((cx + r * 0.92 * __import__('math').cos(ang),
                            cy + r * 0.92 * __import__('math').sin(ang)))
            d.polygon(pts, outline=HONEY + (255,), width=max(1, scale))
    return img


def base_canvas(scale):
    """Cream paper, hex grid, single hairline under the header area."""
    img = Image.new("RGBA", (W * scale, H * scale), BG + (255,))
    honeycomb(img, scale)
    d = ImageDraw.Draw(img)
    d.line([(0, 0), (W * scale, 0)], fill=LINE + (255,), width=max(1, scale))
    return img


def app_logo(scale, px):
    """The shipped DuoLink app icon (dark tile, screens + link), used as-is."""
    art = Image.open(os.path.join(ASSETS, "logo_1024.png")).convert("RGBA")
    size = int(px * scale)
    return art.resize((size, size), Image.LANCZOS)


def centered(draw, y, text, fnt, fill, width=W, scale=1):
    box = draw.textbbox((0, 0), text, font=fnt)
    draw.text(((width * scale - (box[2] - box[0])) / 2 - box[0], y * scale),
              text, font=fnt, fill=fill)
    return box[3] - box[1]


def pill(draw, x, y, w, h, color, scale, label, fnt, text_color):
    radius = h / 2
    draw.rounded_rectangle([x * scale, y * scale, (x + w) * scale, (y + h) * scale],
                           radius=radius * scale, fill=color)
    box = draw.textbbox((0, 0), label, font=fnt)
    tw, th = box[2] - box[0], box[3] - box[1]
    draw.text(((x + w / 2) * scale - tw / 2 - box[0], (y + h / 2) * scale - th / 2 - box[1]),
              label, font=fnt, fill=text_color)


def pill_bitmap(label, color=ACCENT):
    sc = 3
    w, h = 210, 38
    img = Image.new("RGB", (w * sc, h * sc), BG)
    d = ImageDraw.Draw(img)
    pill(d, 0, 0, w, h, color, sc, label, font("segoeuib.ttf", 12 * sc), (250, 246, 238))
    return img


def build_setup():
    sc = SS
    img = base_canvas(sc)
    d = ImageDraw.Draw(img)

    logo_px = 58
    img.alpha_composite(app_logo(sc, logo_px), (int((W / 2 - logo_px / 2) * sc), int(20 * sc)))

    title_f = font("segoeuib.ttf", 21 * sc)
    sub_f = font("segoeui.ttf", 10.5 * sc)
    note_f = font("segoeui.ttf", 8.5 * sc)

    centered(d, 88, "Welcome to DUOLINK", title_f, INK, scale=sc)
    centered(d, 118, "Connect your Android phone and PC like never before.", sub_f, BODY, scale=sc)
    centered(d, 190, "Installs locally  ·  no account  ·  LAN only", note_f, MUTED, scale=sc)
    return img.convert("RGB")


def build_uninstall():
    sc = SS
    img = base_canvas(sc)
    d = ImageDraw.Draw(img)

    logo_px = 54
    img.alpha_composite(app_logo(sc, logo_px), (int((W / 2 - logo_px / 2) * sc), int(22 * sc)))

    title_f = font("segoeuib.ttf", 20 * sc)
    sub_f = font("segoeui.ttf", 10.5 * sc)
    link_f = font("segoeui.ttf", 9 * sc)

    centered(d, 88, "Remove DuoLink?", title_f, INK, scale=sc)
    centered(d, 118, "The app and its local pairing data are removed from this PC.", sub_f, BODY, scale=sc)
    centered(d, 184, "Keep DuoLink", link_f, BODY, scale=sc)
    return img.convert("RGB")


def build_header():
    """Small header bitmap for the directory/installing/uninstalling pages."""
    sc = 3
    w, h = 150, 57
    img = Image.new("RGBA", (w * sc, h * sc), BG + (255,))
    img.alpha_composite(app_logo(sc, 32), (10 * sc, 12 * sc))
    d = ImageDraw.Draw(img)
    d.text((48 * sc, 14 * sc), "DuoLink", font=font("segoeuib.ttf", 11 * sc), fill=INK)
    d.text((48 * sc, 31 * sc), "Phone + PC", font=font("segoeui.ttf", 7 * sc), fill=MUTED)
    return img.convert("RGB")


def build_sidebar():
    sc = 3
    w, h = 164, 314
    img = Image.new("RGBA", (w * sc, h * sc), BG + (255,))
    d = ImageDraw.Draw(img)
    d.line([(0, 0), (0, h * sc)], fill=LINE + (255,), width=max(1, sc))

    size = 62
    img.alpha_composite(app_logo(sc, size), (int((w - size) / 2 * sc), 62 * sc))

    f = font("segoeuib.ttf", 14 * sc)
    box = d.textbbox((0, 0), "DuoLink", font=f)
    d.text(((w * sc - (box[2] - box[0])) / 2 - box[0], 140 * sc), "DuoLink", font=f, fill=INK)

    f2 = font("segoeui.ttf", 7.5 * sc)
    for i, line in enumerate(("Two screens.", "One system.")):
        box = d.textbbox((0, 0), line, font=f2)
        d.text(((w * sc - (box[2] - box[0])) / 2 - box[0], (166 + i * 12) * sc), line, font=f2, fill=BODY)

    f3 = font("segoeui.ttf", 6.5 * sc)
    box = d.textbbox((0, 0), "LAN only · encrypted", font=f3)
    d.text(((w * sc - (box[2] - box[0])) / 2 - box[0], 282 * sc), "LAN only · encrypted", font=f3, fill=MUTED)
    return img.convert("RGB")


TARGETS = {
    "setup-pill.bmp": (210, 38),
    "uninstall-pill.bmp": (210, 38),
    "setup-page.bmp": (W, H),
    "uninstall-page.bmp": (W, H),
    "installerHeader.bmp": (150, 57),
    "installerSidebar.bmp": (164, 314),
    "uninstallerSidebar.bmp": (164, 314),
}


def save(img, name):
    out = os.path.join(HERE, name)
    final = img.resize(TARGETS[name], Image.LANCZOS)
    final.save(out.replace(".bmp", ".png"))
    final.save(out, "BMP")
    print("wrote", name, final.size)


if __name__ == "__main__":
    save(build_setup(), "setup-page.bmp")
    save(pill_bitmap("Install"), "setup-pill.bmp")
    save(build_uninstall(), "uninstall-page.bmp")
    save(pill_bitmap("Uninstall", DANGER), "uninstall-pill.bmp")
    save(build_header(), "installerHeader.bmp")
    save(build_sidebar(), "installerSidebar.bmp")
    save(build_sidebar(), "uninstallerSidebar.bmp")
