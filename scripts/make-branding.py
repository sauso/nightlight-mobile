"""Regenerate every Android / iOS brand image from branding/logo-source.png (the web app's logo: a #778FFD crescent and a
sleeping baby on navy #14142A, 1024 x 1024; the same file lives in nightlight/frontend/branding/).

    python scripts/make-branding.py          (needs Pillow only; about 20 s, most of it the per-pixel pass below)

What it writes (all paths relative to the repo root):
  * Android adaptive icon foregrounds (drawable-*dpi and mipmap-*dpi / ic_launcher_foreground.png), transparent, logo inside the
    66 dp safe circle; the background is the solid navy in drawable/ic_launcher_background.xml
  * Android legacy icons (mipmap-*dpi / ic_launcher.png rounded square, ic_launcher_round.png circle)
  * Android splash screens (drawable/splash.png and drawable-{land,port}-*dpi/splash.png): navy with the logo centred, at the
    sizes the files already have
  * iOS app icon (the logo source itself: iOS wants an opaque full-bleed square) and the three 2732 px splash images
  * www/icon-192.png, the logo tile on the native setup / error pages (rounded, like the web app's tile)

How the transparent logo is made: every pixel of the source is a blend of three flat colours (navy ground, crescent, baby white),
antialiased at the edges, so a pixel is unmixed into three weights (they sum to 1). The logo layer's opacity is the crescent weight
plus the white weight and its colour is the mix of those two. The baby's dark outline is the navy ground colour, so it becomes
see-through, which looks identical on the navy ground every use of this layer sits on.
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "branding" / "logo-source.png"
RES = ROOT / "android" / "app" / "src" / "main" / "res"
NAVY = (0x14, 0x14, 0x2A)
CRESCENT = (0x77, 0x8F, 0xFD)
WHITE = (0xF8, 0xF8, 0xFF)


def logo_layer(path):
    """The source as a transparent RGBA layer cropped to the logo's own bounding box."""
    src = Image.open(path).convert("RGB")
    if src.size != (1024, 1024):
        raise ValueError("logo-source.png must be 1024 x 1024")
    # p - NAVY = b*(CRESCENT - NAVY) + c*(WHITE - NAVY): 3 equations, 2 unknowns, least squares (normal equations).
    u = [CRESCENT[i] - NAVY[i] for i in range(3)]
    v = [WHITE[i] - NAVY[i] for i in range(3)]
    uu, uv, vv = sum(x * x for x in u), sum(u[i] * v[i] for i in range(3)), sum(x * x for x in v)
    det = uu * vv - uv * uv
    out = Image.new("RGBA", src.size, (0, 0, 0, 0))
    spx, opx = src.load(), out.load()
    for y in range(src.height):
        for x in range(src.width):
            p = spx[x, y]
            d = [p[i] - NAVY[i] for i in range(3)]
            bu, bv = sum(d[i] * u[i] for i in range(3)), sum(d[i] * v[i] for i in range(3))
            b = min(max((vv * bu - uv * bv) / det, 0.0), 1.0)
            c = min(max((uu * bv - uv * bu) / det, 0.0), 1.0)
            alpha = min(b + c, 1.0)
            if alpha < 0.02:
                continue
            col = tuple(round((b * CRESCENT[i] + c * WHITE[i]) / (b + c)) for i in range(3))
            opx[x, y] = col + (round(alpha * 255),)
    return out.crop(out.getbbox())


def place(canvas, logo, fraction):
    """Paste the logo centred, its longer side being `fraction` of the canvas's shorter side."""
    side = round(min(canvas.size) * fraction)
    scale = side / max(logo.size)
    sized = logo.resize((max(1, round(logo.width * scale)), max(1, round(logo.height * scale))), Image.Resampling.LANCZOS)
    canvas.alpha_composite(sized, ((canvas.width - sized.width) // 2, (canvas.height - sized.height) // 2))
    return canvas


def navy(size):
    return Image.new("RGBA", size, NAVY + (255,))


def rounded(img, radius_fraction):
    mask = Image.new("L", img.size, 0)
    r = round(min(img.size) * radius_fraction)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius=r, fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def circle(img):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, img.width - 1, img.height - 1), fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def save(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", path.relative_to(ROOT), img.size)


logo = logo_layer(SOURCE)

# Adaptive icon foreground: a 108 dp canvas of which only the middle 66 dp circle is guaranteed visible, so the logo's longer side
# is 56 % of the canvas (60 dp), inside that circle with room for the launcher's mask and parallax.
for density, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    fg = place(Image.new("RGBA", (px, px), (0, 0, 0, 0)), logo, 0.56)
    save(fg, RES / f"drawable-{density}" / "ic_launcher_foreground.png")
    save(fg, RES / f"mipmap-{density}" / "ic_launcher_foreground.png")

# Legacy (pre-Android 8) icons: 48 dp, a rounded square and a circle.
for density, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    save(rounded(place(navy((px, px)), logo, 0.72), 0.22), RES / f"mipmap-{density}" / "ic_launcher.png")
    save(circle(place(navy((px, px)), logo, 0.62)), RES / f"mipmap-{density}" / "ic_launcher_round.png")

# Android splash: navy with the logo centred; keep each file's existing size (they are laid out per density and orientation).
for path in sorted(RES.glob("drawable*/splash.png")):
    with Image.open(path) as existing:
        size = existing.size
    save(place(navy(size), logo, 0.30).convert("RGB"), path)

# iOS: the app icon is the opaque source itself; the three splash images are 2732 px squares shown scaleAspectFill.
ios = ROOT / "ios" / "App" / "App" / "Assets.xcassets"
save(Image.open(SOURCE).convert("RGB"), ios / "AppIcon.appiconset" / "AppIcon-512@2x.png")
for name in ("splash-2732x2732.png", "splash-2732x2732-1.png", "splash-2732x2732-2.png"):
    save(place(navy((2732, 2732)), logo, 0.22).convert("RGB"), ios / "Splash.imageset" / name)

# The tile on the native setup / error pages.
save(rounded(Image.open(SOURCE).convert("RGBA").resize((192, 192), Image.Resampling.LANCZOS), 0.25), ROOT / "www" / "icon-192.png")
