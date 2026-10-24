# Favicon generator

`generate_favicon.py` regenerates both favicons shipped by the [`favicon` module](../../../modules/favicon),
under `modules/favicon/src/main/resources/SLING-INF/content/`:

- `favicon.ico` — the raster icon, at 16, 32 and 48 pixels.
- `favicon.svg` — the same mark as vector art.

The script is the source of truth for both: the artwork is described by a handful of numbers in a table, not
by an image file, and the `.svg` is emitted from that same table so the two cannot drift apart.

The mark is a QP monogram built around the logo's token: a thin blue **Q** (a ring, plus a tail running
right along the baseline) enclosing a solid crimson **P**. The P is a filled disc, the token, with a straight
stem running down the Q's lower left to the shared baseline. The notch between the stem and the Q's outer
curve is filled red, a half serif that carries the P's foot along the baseline to where the Q's tail begins,
and the foot's outer corner is rounded so it rolls with the Q. A clear gap separates the disc from the ring.

It replaced the earlier path-and-haloed-token mark, whose red disc inside a red ring was routinely mistaken
for the "recording" indicator that browsers and Teams show in a tab. The red disc is back, deliberately, but
the ring around it is blue and the disc carries a stem and a tail. **Keep the ring a contrasting colour**: a
red or pink ring around this disc is the recording indicator again.

The Q is a mid blue, `#4A66B5`, not the logo's navy, and it is the same in both colour schemes. A favicon
cannot tell what it is drawn on: inside an SVG icon `prefers-color-scheme` follows the operating system, not
the browser's theme, so a dark system with a light browser (or the reverse) gets the wrong variant, and navy
on a dark tab strip is nearly invisible. The blue keeps at least 4:1 contrast on Chrome's light tab colours
and 2:1 on its dark ones, as the crimson P does.

The artwork is described in design units (the 48-unit grid it was drawn on) and fitted to each size, then
rasterized directly with supersampled coverage. Only the standard library is used: a 32bpp `.ico` is just
bottom-up BMP data behind a small directory, and a ring, a disc, two bars and two curved corners anti-alias
cleanly — no Pillow or ImageMagick needed.

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

The shape lives in the `DESIGN` table: centre, the Q's outer radius, weight and tail length, the gap between
disc and ring, the stem width and the foot's corner radius. The P's disc takes whatever radius is left inside
the ring and the gap. Per-size fitting lives in `GEOMETRY`: the margin, and optionally an explicit scale, Q
weight and gap. Things to preserve when tuning:

- `layout()` shifts the whole mark by under half a pixel so the **baseline and the P stem's left edge** — the
  long straight edges — land on pixel boundaries, and trims or extends the tail by under half a pixel so its
  square end does too.
- **16px is hand-hinted.** At design weights the ring would be under a pixel and the gap about 1.2, both
  blurred, and the tab would show a red dot in a smudge: the recording look. Instead the Q's outer radius is
  exactly 6.5 pixels about a pixel-centred point, and the ring and the gap are exactly one pixel each, so every
  circular edge sits on a pixel boundary at the cardinal points. Keep the ring and the gap at a pixel or more.
- Red is drawn over blue, decided per coverage sample rather than by compositing two anti-aliased layers, so
  the seam where the stem crosses the ring has no blended fringe.

## About the SVG

It carries the 48px fit of the design as filled outlines (one path per letter, the ring as an opposed circle
pair under the non-zero rule), with colours as presentation attributes and no stylesheet.

A tuned tab-size mark would need a `max-width` media query and CSS geometry
properties (`d: path()`, `cx`, `r`), which browsers do not reliably honour in the favicon path, so a single
artwork serves every size. Browsers that prefer the `.svg` (Chrome, Edge, Firefox) therefore draw a 16px tab
from the 48px fit, with a slightly softer ring than the hand-hinted 16px rendering in the `.ico`.

Browsers that ignore SVG icons altogether (Safari) use the `.ico`, which is why both are shipped and both
are declared:

```html
<link rel="icon" href="/favicon.ico" sizes="32x32" />
<link rel="icon" href="/favicon.svg" type="image/svg+xml" />
```

Those live in `libs/data/Content/header.html` (the app shell) and the 404 error page. Without them only
`/favicon.ico` is found, since that is the sole path browsers probe by convention. `/favicon.svg` also needs
an entry in `sling.auth.requirements`
([`sling-configuration.json`](../../../packaging/slingfeature/src/main/features/core/sling-configuration.json))
so it is readable before login, and a `favicon.svg.json` descriptor granting anonymous read, both of which
mirror what the `.ico` already had.

## Content negotiation

`/favicon.ico` is the one path browsers probe by convention, so a client that can draw SVG should get the
vector icon from it too. `FaviconServlet` (in `modules/favicon`) does that: a request whose `Accept` header
names `image/svg+xml` — a wildcard does not count, and `q=0` refuses — is answered with `/favicon.svg`;
every other request gets the `.ico`. Both answers carry `Vary: Accept`, so a cache keeps them apart.

The servlet is bound by resource type (`iap/Favicon`, declared by `favicon.ico.json`), not mounted at the
path. A path-mounted servlet would shadow the node and the access control that makes the icon readable
before login.

## Deploying it

Sling-Initial-Content will not overwrite a `/favicon.ico` or `/favicon.svg` node that already exists, so
`mvn install` plus a restart leaves a running instance serving the old icons, and without the resource type
the negotiation never engages. Either start with a fresh data directory, or post the new files over the
existing nodes and set `sling:resourceType=iap/Favicon` on `/favicon.ico`.
