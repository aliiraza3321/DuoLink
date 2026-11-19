"""
Generates the DuoLink installer artwork.

The installer carries the same cream-and-ink identity as the app: warm paper, a
faint honeycomb, the product mark, one action pill, and a soft panel for the
choices. Everything a person can *do* (the checkbox, the install folder line,
"Change", "What this installs") is a real nsDialogs control placed on top of
this artwork by build/installer.nsh - the bitmap only supplies the paper, the
type and the panel they sit in. Nothing interactive is ever painted.

Run from desktop/:  python build/make-installer-art.py
"""

import math
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(HERE), "renderer", "assets")

# The nsDialogs page electron-builder hands us is 450x228. Drawing 490x238 used
# to push the right 40px and the bottom 10px of every page outside the dialog,
# which is why the small print looked pinned to the window edge.
W, H = 450, 228
SS = 4                   # supersampling factor

BG = (243, 238, 226)      # cream paper
HONEY = (231, 222, 204)   # hex grid lines
INK = (30, 27, 22)        # headings
BODY = (95, 90, 78)       # body copy
MUTED = (140, 132, 114)   # fine print
ACCENT = (140, 102, 32)   # dark gold pill
LINE = (222, 214, 198)
PANEL = (251, 248, 241)   # soft card behind the choices
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
                ang = (60 * i - 30) * math.pi / 180
                pts.append((cx + r * 0.92 * math.cos(ang),
                            cy + r * 0.92 * math.sin(ang)))
            d.polygon(pts, outline=HONEY + (255,), width=max(1, scale))
    return img


def base_canvas(scale):
    """Cream paper with the honeycomb; no hard rules, the header sits above."""
    img = Image.new("RGBA", (W * scale, H * scale), BG + (255,))
    honeycomb(img, scale)
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


def pill_bitmap(label, color=ACCENT, size=(210, 38)):
    sc = 3
    w, h = size
    img = Image.new("RGB", (w * sc, h * sc), BG)
    d = ImageDraw.Draw(img)
    if color is None:
        # Outlined pill: cream fill, hairline ink edge, ink label.
        d.rounded_rectangle([0, 0, w * sc - 1, h * sc - 1], radius=(h / 2) * sc,
                            outline=(206, 198, 182), width=max(1, sc), fill=BG)
        pill(d, 1, 1, w - 2, h - 2, BG, sc, label, font("segoeuib.ttf", 11 * sc), INK)
    else:
        pill(d, 0, 0, w, h, color, sc, label, font("segoeuib.ttf", 11 * sc), (250, 246, 238))
    return img


# Panel that the real controls (checkbox, folder line, links) sit inside.
PANEL_X, PANEL_Y, PANEL_W, PANEL_H = 18, 158, 414, 62


def build_setup():
    """Welcome page: the mark, the product name, one Install pill, and a panel
    of real choices underneath (NSIS places those controls on top)."""
    sc = SS
    img = base_canvas(sc)
    d = ImageDraw.Draw(img)

    logo_px = 46
    img.alpha_composite(app_logo(sc, logo_px), (int((W / 2 - logo_px / 2) * sc), int(6 * sc)))

    title_f = font("segoeuib.ttf", 22 * sc)
    sub_f = font("segoeui.ttf", 10 * sc)
    chips_f = font("segoeuib.ttf", 7 * sc)

    centered(d, 56, "DuoLink", title_f, INK, scale=sc)
    centered(d, 86, "Your Android phone and your PC, working as one.", sub_f, BODY, scale=sc)
    centered(d, 102, "MESSAGES   CALLS   FILES   MIRRORING   WEBCAM   APPS", chips_f, MUTED, scale=sc)

    # The Install pill is a separate clickable bitmap NSIS overlays; the panel
    # behind the options is drawn here so the native controls blend in.
    d.rounded_rectangle(
        [PANEL_X * sc, PANEL_Y * sc, (PANEL_X + PANEL_W) * sc, (PANEL_Y + PANEL_H) * sc],
        radius=10 * sc, fill=PANEL + (255,), outline=LINE + (255,), width=max(1, sc)
    )
    return img.convert("RGB")


def build_ready():
    """Final page: DuoLink is ready, the Android download QR, and its link."""
    sc = SS
    img = base_canvas(sc)
    d = ImageDraw.Draw(img)

    title_f = font("segoeuib.ttf", 18 * sc)
    sub_f = font("segoeui.ttf", 9 * sc)
    link_f = font("segoeui.ttf", 7.5 * sc)

    centered(d, 8, "DuoLink is ready", title_f, INK, scale=sc)
    centered(d, 34, "Next, get the Android app. Scan this code with your", sub_f, BODY, scale=sc)
    centered(d, 47, "phone's camera to open its download page.", sub_f, BODY, scale=sc)

    qr_path = os.path.join(HERE, "qr-ready.png")
    if os.path.exists(qr_path):
        qr_px = 92
        qr = Image.open(qr_path).convert("RGB").resize((qr_px * sc, qr_px * sc), Image.NEAREST)
        qr_x = int(W / 2 - qr_px / 2)
        qr_y = 64
        d.rounded_rectangle(
            [(qr_x - 5) * sc, (qr_y - 5) * sc, (qr_x + qr_px + 5) * sc, (qr_y + qr_px + 5) * sc],
            radius=5 * sc, outline=LINE, width=max(1, sc), fill=(252, 250, 245)
        )
        img.paste(qr, (qr_x * sc, qr_y * sc))

    centered(d, 164, "github.com/aliiraza3321/DuoLink/releases/latest", link_f, ACCENT, scale=sc)
    return img.convert("RGB")


def build_uninstall():
    sc = SS
    img = base_canvas(sc)
    d = ImageDraw.Draw(img)

    logo_px = 48
    img.alpha_composite(app_logo(sc, logo_px), (int((W / 2 - logo_px / 2) * sc), int(18 * sc)))

    title_f = font("segoeuib.ttf", 19 * sc)
    sub_f = font("segoeui.ttf", 10 * sc)

    centered(d, 78, "Uninstall DuoLink?", title_f, INK, scale=sc)
    centered(d, 106, "This removes DuoLink and its settings and pairings on this PC.", sub_f, BODY, scale=sc)
    centered(d, 140, "Files you received stay in Downloads\\DuoLink.", sub_f, MUTED, scale=sc)
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
    "ready-page.bmp": (W, H),
    "ready-open.bmp": (170, 34),
    "ready-close.bmp": (120, 34),
    "uninstall-page.bmp": (W, H),
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
    save(build_ready(), "ready-page.bmp")
    save(pill_bitmap("Open DuoLink", ACCENT, (170, 34)), "ready-open.bmp")
    save(pill_bitmap("Close", None, (120, 34)), "ready-close.bmp")
    save(build_uninstall(), "uninstall-page.bmp")
    save(pill_bitmap("Uninstall", DANGER), "uninstall-pill.bmp")
    save(build_sidebar(), "installerSidebar.bmp")
    save(build_sidebar(), "uninstallerSidebar.bmp")
