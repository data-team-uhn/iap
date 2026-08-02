# Favicon generator

`generate_favicon.py` regenerates both favicons shipped by the [`favicon` module](../../../modules/favicon),
under `modules/favicon/src/main/resources/SLING-INF/content/`:

- `favicon.ico` — the raster icon, at 16, 32 and 48 pixels.
- `favicon.svg` — the same mark as vector art, with a **dark-scheme variant**.

The script is the source of truth for both: the artwork is described by a handful of numbers in a table, not
by an image file, and the `.svg` is emitted from that same table so the two cannot drift apart.

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

`--out` and `--svg-out` write elsewhere, leaving the module's copies alone.

## Changing the artwork

Edit the `GEOMETRY` table: per size, the path thickness, the halo's outer radius and stroke, the token
radius, and the margin kept clear at the left and right edges. Two things to preserve when tuning:

- The mark is centred on `cy = size / 2`, an exact pixel boundary, so an **even** path thickness lands
  squarely on two rows rather than straddling four at half coverage.
- 16px drops the halo (`r_out: 0`). A sub-pixel ring at 45% opacity only muddies the token at that size,
  so the pixel-snapped disc carries the mark on its own.

## About the SVG

It carries the 48px geometry as presentation attributes, in the light palette, and adds two stylesheet
overrides:

- `prefers-color-scheme: dark` repaints the path in `#A3A9BC` and lifts the halo to 60% opacity — the
  dark-scheme palette of `logo-dark.svg`. Navy on a dark tab strip is nearly invisible, which is the whole
  reason this file exists; the token keeps the same red in both schemes, as the logo does.
- `max-width: 24px` switches to the tuned 16px mark. Wherever the `.svg` is supported the browser uses it
  for the tab too, rasterizing it at 16px on a 1x display, and a faithful copy of the 48px artwork does not
  survive that: the halo becomes a pink blob and the sub-pixel path goes grey and soft. Media queries inside
  an SVG resolve against the viewport it is drawn into, so this swaps in `GEOMETRY[16]` (scaled into the
  48-unit viewBox) exactly when the icon is drawn tab-sized. Support for width queries in the favicon path
  is not universal; where it is ignored the faithful mark is used at every size, the same trade-off any
  single-artwork SVG favicon makes.

Browsers that ignore SVG icons altogether (Safari) use the `.ico`, which is why both are shipped and both
are declared:

```html
<link rel="icon" href="/favicon.ico" sizes="32x32" />
<link rel="icon" href="/favicon.svg" type="image/svg+xml" />
```

Those live in `libs/iap/Content/header.html` (the app shell) and the 404 error page. Without them only
`/favicon.ico` is found, since that is the sole path browsers probe by convention. `/favicon.svg` also needs
an entry in `sling.auth.requirements`
([`sling-configuration.json`](../../../packaging/slingfeature/src/main/features/core/sling-configuration.json))
so it is readable before login, and a `favicon.svg.json` descriptor granting anonymous read, both of which
mirror what the `.ico` already had.

## Deploying it

Sling-Initial-Content will not overwrite a `/favicon.ico` or `/favicon.svg` node that already exists, so
`mvn install` plus a restart leaves a running instance serving the old icons. Either start with a fresh data
directory, or post the new files over the existing nodes.
