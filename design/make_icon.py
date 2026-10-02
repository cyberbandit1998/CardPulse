#!/usr/bin/env python3
"""
Builds Android adaptive-icon layers from the CardPulse artwork.

The artwork is a rounded-square app icon: a fan of red cards on dark glass inside a red ring. Android masks
launcher icons itself (circle, squircle, ...), so the ring and the glass are dropped here: the cards are cut
out with a soft edge (their glow is kept) and sit on a flat dark background layer.

    python design/make_icon.py design/icon-source.webp app/src/main/res [preview dir]

The geometry (where the ring is, how bright a card rim is) is tuned to this one piece of artwork; a different
design needs the constants in card_silhouette() adjusted. Needs Pillow and numpy.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

CANVAS_DP = 108            # an adaptive icon layer is 108 x 108 dp
SAFE_DP = 66               # content inside a circle this wide is never clipped by a launcher's mask
MASTER_PX = 1080           # 10 px per dp; each density is scaled down from this
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def card_silhouette(rgb: Image.Image) -> Image.Image:
    """A solid mask of the three cards, without the container's ring or glass highlights."""
    width, height = rgb.size
    red = np.asarray(rgb)[..., 0].astype(np.int16)

    # The ring hugs the artwork's edge; stay well inside it. Card rims are bright red, the glass is not.
    inside_ring = Image.new("L", rgb.size, 0)
    ImageDraw.Draw(inside_ring).rounded_rectangle(
        [86 + 40, 77 + 40, width - 86 - 40, height - 108 - 40], radius=230, fill=255
    )
    rims = Image.fromarray((((red > 150) & (np.asarray(inside_ring) > 0)) * 255).astype("uint8"), "L")

    # Seal pinholes in the rims, then fill everything the rims enclose (the dark card faces).
    closed = rims.filter(ImageFilter.MaxFilter(11)).filter(ImageFilter.MinFilter(11))
    outside = ImageChops.invert(closed)
    ImageDraw.floodfill(outside, (0, 0), 128)
    return Image.fromarray(((np.asarray(outside) != 128) * 255).astype("uint8"), "L")


def enclosing_circle(mask: Image.Image) -> tuple[float, float, float]:
    """Centre and radius of (nearly) the smallest circle around the mask (Badoiu-Clarkson)."""
    ys, xs = np.nonzero(np.asarray(mask.filter(ImageFilter.FIND_EDGES)) > 0)
    points = np.stack([xs, ys], axis=1).astype(float)
    centre = points.mean(axis=0)
    for i in range(1, 2000):
        far = points[np.argmax(((points - centre) ** 2).sum(axis=1))]
        centre += (far - centre) / (i + 1)
    radius = float(np.sqrt(((points - centre) ** 2).sum(axis=1).max()))
    return float(centre[0]), float(centre[1]), radius


def blur(image: Image.Image, sigma: float) -> Image.Image:
    return image.filter(ImageFilter.GaussianBlur(sigma))


def build_foreground(rgb: Image.Image, silhouette: Image.Image, feather: float = 9.0) -> Image.Image:
    """The artwork with everything but the cards (and their glow) faded out, centred on the 108 dp canvas."""
    # 1 on the cards, falling smoothly to 0 over roughly 2 x feather pixels outside them.
    alpha = np.clip(np.asarray(blur(silhouette, feather)).astype(np.float32) / 255 * 2, 0, 1)
    layer = rgb.convert("RGBA")
    layer.putalpha(Image.fromarray((alpha * 255).astype("uint8"), "L"))

    cx, cy, radius = enclosing_circle(silhouette)
    scale = (SAFE_DP / CANVAS_DP * MASTER_PX) / (2 * radius)
    size = round(rgb.width * scale)
    layer = layer.resize((size, size), Image.LANCZOS)
    canvas = Image.new("RGBA", (MASTER_PX, MASTER_PX), (0, 0, 0, 0))
    canvas.alpha_composite(layer, (round(MASTER_PX / 2 - cx * scale), round(MASTER_PX / 2 - cy * scale)))
    return canvas


def background_colour(rgb: Image.Image, silhouette: Image.Image) -> tuple[int, int, int]:
    """The artwork's own dark: the median colour in a band just outside the cards' glow."""
    near = np.asarray(silhouette.filter(ImageFilter.MaxFilter(121))) > 0
    far = np.asarray(silhouette.filter(ImageFilter.MaxFilter(61))) > 0
    band = near & ~far
    inner = Image.new("L", rgb.size, 0)
    ImageDraw.Draw(inner).rounded_rectangle([200, 200, rgb.width - 200, rgb.height - 200], radius=200, fill=255)
    band &= np.asarray(inner) > 0
    pixels = np.asarray(rgb)[band]
    return tuple(int(v) for v in np.median(pixels, axis=0))


def monochrome(foreground: Image.Image) -> Image.Image:
    """White glyph for themed icons: bright rims, sparkles and the pulse line; the dark card faces drop out."""
    value = np.asarray(foreground.convert("RGB")).max(axis=2).astype(np.float32)
    alpha = np.asarray(foreground)[..., 3].astype(np.float32) / 255
    # Steep curve so the soft glow disappears and only the crisp shapes remain.
    shape = np.clip((value - 200) / 40, 0, 1) * alpha
    out = np.zeros((*shape.shape, 4), dtype=np.uint8)
    out[..., :3] = 255
    out[..., 3] = (shape * 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def write_densities(master: Image.Image, res: Path, name: str) -> None:
    for density, factor in DENSITIES.items():
        folder = res / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        px = round(CANVAS_DP * factor)
        master.resize((px, px), Image.LANCZOS).save(folder / f"{name}.png", optimize=True)


def preview(foreground: Image.Image, bg: tuple[int, int, int], out: Path) -> None:
    """The icon as a launcher would show it: the 72 dp visible window, under a few common masks."""
    px = 432
    base = Image.new("RGBA", (MASTER_PX, MASTER_PX), bg + (255,))
    base.alpha_composite(foreground)
    base = base.resize((px, px), Image.LANCZOS)
    visible = round(px * 72 / 108)
    off = (px - visible) // 2
    window = base.crop((off, off, off + visible, off + visible))

    def masked(shape: str) -> Image.Image:
        mask = Image.new("L", (visible * 4, visible * 4), 0)
        draw = ImageDraw.Draw(mask)
        full = [0, 0, visible * 4 - 1, visible * 4 - 1]
        if shape == "circle":
            draw.ellipse(full, fill=255)
        elif shape == "squircle":
            n = 4.0
            xs = np.linspace(-1, 1, visible * 4)
            gx, gy = np.meshgrid(xs, xs)
            mask = Image.fromarray(((np.abs(gx) ** n + np.abs(gy) ** n <= 1) * 255).astype("uint8"), "L")
        else:
            draw.rounded_rectangle(full, radius=visible * 4 // 5, fill=255)
        mask = mask.resize((visible, visible), Image.LANCZOS)
        tile = Image.new("RGBA", (visible + 40, visible + 40), (232, 232, 236, 255))
        tile.paste(window, (20, 20), mask)
        return tile

    shapes = ["circle", "squircle", "rounded"]
    sheet = Image.new("RGBA", ((visible + 40) * len(shapes) + (px + 40), px + 40), (232, 232, 236, 255))
    for i, shape in enumerate(shapes):
        sheet.paste(masked(shape), (i * (visible + 40), 0))
    guide = base.copy()
    draw = ImageDraw.Draw(guide)
    for dp, colour in ((66, (0, 255, 120, 255)), (72, (255, 200, 0, 255))):
        r = px * dp / 108 / 2
        draw.ellipse([px / 2 - r, px / 2 - r, px / 2 + r, px / 2 + r], outline=colour, width=2)
    sheet.paste(guide, ((visible + 40) * len(shapes) + 20, 20))
    sheet.convert("RGB").save(out)


def main() -> None:
    source, res = Path(sys.argv[1]), Path(sys.argv[2])
    preview_dir = Path(sys.argv[3]) if len(sys.argv) > 3 else None

    rgb = Image.open(source).convert("RGB")      # the file is tagged sRGB, so no conversion is needed
    silhouette = card_silhouette(rgb)
    foreground = build_foreground(rgb, silhouette)
    bg = background_colour(rgb, silhouette)
    mono = monochrome(foreground)

    write_densities(foreground, res, "ic_launcher_foreground")
    write_densities(mono, res, "ic_launcher_monochrome")
    values = res / "values"
    values.mkdir(parents=True, exist_ok=True)
    (values / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n</resources>\n' % bg
    )
    print("background colour: #%02X%02X%02X" % bg)

    if preview_dir:
        preview_dir.mkdir(parents=True, exist_ok=True)
        preview(foreground, bg, preview_dir / "preview.png")
        # What the themed (monochrome) icon looks like when a launcher tints it.
        tint = Image.new("RGBA", (MASTER_PX, MASTER_PX), (66, 52, 90, 255))
        glyph = Image.new("RGBA", (MASTER_PX, MASTER_PX), (235, 220, 255, 255))
        glyph.putalpha(mono.getchannel("A"))
        tint.alpha_composite(glyph)
        tint.resize((432, 432), Image.LANCZOS).convert("RGB").save(preview_dir / "preview_themed.png")


if __name__ == "__main__":
    main()
