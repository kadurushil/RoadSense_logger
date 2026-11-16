"""Update RoadSense Android launcher icons and in-app logo from a source image.

Usage:
    python scripts/update_app_icon.py <path-to-image>
"""

import argparse
from collections import deque
from pathlib import Path
from PIL import Image, ImageDraw

REPO_ROOT = Path(__file__).resolve().parent.parent
RES_DIR = REPO_ROOT / "app" / "src" / "main" / "res"

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def _rgb_to_hex(rgb: tuple[int, int, int]) -> str:
    return "#{:02X}{:02X}{:02X}".format(*rgb)


def _strip_light_corners(im: Image.Image) -> Image.Image:
    """Flood-fill near-white outer corners to transparent when present."""
    w, h = im.size
    px = im.load()
    corners = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
    if not any(px[x, y][0] > 180 and px[x, y][1] > 180 and px[x, y][2] > 180 for x, y in corners):
        return im

    visited = bytearray(w * h)
    q = deque(corners)
    for x, y in q:
        visited[y * w + x] = 1

    while q:
        x, y = q.popleft()
        r, g, b, _ = px[x, y]
        if r > 180 and g > 180 and b > 180:
            px[x, y] = (0, 0, 0, 0)
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < w and 0 <= ny < h and not visited[ny * w + nx]:
                    visited[ny * w + nx] = 1
                    q.append((nx, ny))

    bbox = im.getbbox()
    if not bbox:
        return im
    cropped = im.crop(bbox)
    bw, bh = cropped.size
    side = max(bw, bh)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(cropped, ((side - bw) // 2, (side - bh) // 2))
    return sq


def _sample_background(im: Image.Image) -> tuple[int, int, int]:
    """Sample a representative background color from the image."""
    w, h = im.size
    px = im.load()
    for x, y in ((4, 4), (w - 5, 4), (4, h - 5), (w // 2, max(10, h // 20))):
        r, g, b, a = px[x, y]
        if a > 200:
            return (r, g, b)
    return (21, 36, 57)


def update_icons(source_path: Path) -> None:
    im = Image.open(source_path).convert("RGBA")
    im = _strip_light_corners(im)
    bg_rgb = _sample_background(im)
    bg_hex = _rgb_to_hex(bg_rgb)

    # 1. In-app logo (LaunchBuildOverlay)
    nodpi_dir = RES_DIR / "drawable-nodpi"
    nodpi_dir.mkdir(parents=True, exist_ok=True)
    im.resize((512, 512), Image.Resampling.LANCZOS).save(nodpi_dir / "ic_roadsense_logo.png", "PNG")

    # 2. Adaptive icon foreground (432x432, scaled to 76% so text/artwork stays inside safe zone)
    fg_canvas = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    fg_size = 328
    fg_logo = im.resize((fg_size, fg_size), Image.Resampling.LANCZOS)
    offset = (432 - fg_size) // 2
    fg_canvas.paste(fg_logo, (offset, offset), fg_logo)
    fg_canvas.save(nodpi_dir / "ic_launcher_foreground.png", "PNG")

    # 3. Adaptive icon background XML
    bg_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        '    <path\n'
        f'        android:fillColor="{bg_hex}"\n'
        '        android:pathData="M0,0h108v108h-108z" />\n'
        "</vector>\n"
    )
    (RES_DIR / "drawable" / "ic_launcher_background.xml").write_text(bg_xml, encoding="utf-8")

    # 4. Legacy / density mipmap WebP icons
    for folder, size in DENSITIES.items():
        out_dir = RES_DIR / folder
        out_dir.mkdir(parents=True, exist_ok=True)

        im.resize((size, size), Image.Resampling.LANCZOS).save(
            out_dir / "ic_launcher.webp", "WEBP", quality=95, method=6
        )

        round_bg = Image.new("RGBA", (size, size), (*bg_rgb, 255))
        inner = im.resize((int(size * 0.92), int(size * 0.92)), Image.Resampling.LANCZOS)
        off = (size - inner.width) // 2
        round_bg.paste(inner, (off, off), inner)

        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        round_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        round_icon.paste(round_bg, (0, 0), mask)
        round_icon.save(out_dir / "ic_launcher_round.webp", "WEBP", quality=95, method=6)

    print(f"Updated RoadSense icons from {source_path.name} (background={bg_hex})")


def main() -> None:
    parser = argparse.ArgumentParser(description="Update RoadSense app launcher icons and in-app logo.")
    parser.add_argument("image", type=Path, help="Path to square PNG/JPG source logo image")
    args = parser.parse_args()
    if not args.image.is_file():
        raise FileNotFoundError(f"Image not found: {args.image}")
    update_icons(args.image)


if __name__ == "__main__":
    main()
