#!/usr/bin/env python3
"""Makes the store images pass Google Play's checks: no alpha channel anywhere.

    python3 tools/flatten_store_images.py [DIR ...]      # default: fastlane/metadata/android

Screenshots (images/phoneScreenshots/*.png) become 24-bit RGB PNGs; any transparent area is
filled with the app's dark background (#0A0A0A, AmberPalette's dark background). The icon
(images/icon.png, 512x512) becomes a full square on the launcher icon's background (#FFB020,
ic_launcher_background.xml): Play applies its own mask, so the round corners of a pre-masked
icon would show as black or white. File names stay. Prints mode and size before and after.
Needs Pillow (a plain `python3`, not `-I`: Pillow normally lives in the user's site-packages).
"""
import glob
import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is missing: install python3-pillow (or pip install --user Pillow)")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCREENSHOT_BACKGROUND = (0x0A, 0x0A, 0x0A)  # AmberTheme.kt, dark background
ICON_BACKGROUND = (0xFF, 0xB0, 0x20)  # res/drawable/ic_launcher_background.xml
ICON_SIZE = (512, 512)


def describe(path):
    with Image.open(path) as im:
        return "%s %dx%d" % (im.mode, im.width, im.height)


def flatten(path, background):
    with Image.open(path) as im:
        im.load()
        rgba = im.convert("RGBA")
    canvas = Image.new("RGBA", rgba.size, background + (255,))
    canvas.alpha_composite(rgba)
    canvas.convert("RGB").save(path, format="PNG", optimize=True)


def main():
    roots = sys.argv[1:] or [os.path.join(ROOT, "fastlane", "metadata", "android")]
    files = []
    for root in roots:
        for pattern, background in (
            ("*/images/phoneScreenshots/*.png", SCREENSHOT_BACKGROUND),
            ("*/images/icon.png", ICON_BACKGROUND),
        ):
            files += [(p, background) for p in sorted(glob.glob(os.path.join(root, pattern)))]
    if not files:
        sys.exit("no store images found under %s" % ", ".join(roots))
    for path, background in files:
        before = describe(path)
        flatten(path, background)
        after = describe(path)
        if path.endswith("icon.png") and after.split(" ")[1] != "%dx%d" % ICON_SIZE:
            sys.exit("%s is %s, Play wants 512x512" % (path, after))
        print("%s: %s -> %s" % (os.path.relpath(path, ROOT), before, after))


if __name__ == "__main__":
    main()
