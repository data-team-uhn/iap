# Favicon generator

`generate_favicon.py` regenerates the favicon shipped by the [`favicon` module](../../../modules/favicon),
`modules/favicon/src/main/resources/SLING-INF/content/favicon.ico`. It is the source of truth for that
binary: the artwork is described by a handful of numbers in the script, not by an image file.

The mark is the small-size counterpart of the QuorumPath application logo
(`modules/homepage/src/main/media/SLING-INF/content/libs/iap/resources/media/default/logo-light.svg`):
a navy path running from the left edge into the centre of a red token — a filled disc with a translucent
halo ring — sitting at the end of the path.

It is deliberately **not** a scaled copy of the logo. At 16–48 pixels the logo's hairlines vanish and
fractional strokes turn into grey smears, so the path thickness, token size, halo and margins are tuned
per size, and the artwork is rasterized directly. Only the standard library is used: a 32bpp `.ico` is
just bottom-up BMP data behind a small directory, and two circles plus a thick segment anti-alias cleanly
from supersampled coverage — no Pillow or ImageMagick needed.

Run from the repository root:

```bash
python3 tools/dev/favicon/generate_favicon.py
```

To eyeball the result, which is worth doing after any change, write PNG previews (scaled up, plus actual
size — the one that really matters) and an ASCII pixel map:

```bash
python3 tools/dev/favicon/generate_favicon.py --preview-dir /tmp/favicon --ascii
```

`--out` writes the `.ico` elsewhere, leaving the module's copy alone.

## Changing the artwork

Edit the `GEOMETRY` table: per size, the path thickness, the halo's outer radius and stroke, the token
radius, and the margin kept clear at the left and right edges. Two things to preserve when tuning:

- The mark is centred on `cy = size / 2`, an exact pixel boundary, so an **even** path thickness lands
  squarely on two rows rather than straddling four at half coverage.
- 16px drops the halo (`r_out: 0`). A sub-pixel ring at 45% opacity only muddies the token at that size,
  so the pixel-snapped disc carries the mark on its own.

## Deploying it

Sling-Initial-Content will not overwrite a `/favicon.ico` node that already exists, so `mvn install` plus a
restart leaves a running instance serving the old icon. Either start with a fresh data directory, or post
the new file over the existing node.
