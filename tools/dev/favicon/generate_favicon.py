#!/usr/bin/env python
# -*- coding: utf-8 -*-

"""
   Copyright 2026 DATA @ UHN. See the NOTICE file
   distributed with this work for additional information
   regarding copyright ownership.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
"""

# Regenerates the two QuorumPath favicons shipped by the `favicon` module: `favicon.ico` (16,
# 32 and 48 pixels) and `favicon.svg`, which is the same mark as vector art. The mark is a QP
# monogram built around the logo's token: a thin navy Q (a ring, plus a
# tail running right along the baseline) enclosing a solid crimson P. The P is a filled disc, the
# token, with a stem running down the Q's lower left to the shared baseline; the notch between the
# stem and the Q's outer curve is filled red, a half serif that carries the P's foot along the
# baseline to where the Q's tail begins, and the foot's outer corner is rounded so it rolls with
# the Q. A clear gap separates the disc from the ring, and a white keyline runs round the whole.
#
# Keep the ring a contrasting colour: a red or pink ring around the red disc reads as the
# "recording" indicator that browsers and Teams show in a tab.
#
# Everything is described in DESIGN units (the 48-unit grid the mark was drawn on) and placed per
# size by GEOMETRY. Everything below is the standard library only — no Pillow, no ImageMagick —
# because a 32bpp .ico is just bottom-up BMP data behind a small directory, and the mark is a ring,
# a disc, two bars and two curved corners, which anti-alias cleanly from supersampled coverage.
# The .svg is emitted from the same tables, so the two cannot drift apart.
#
# Run from the repository root:
#   python3 tools/dev/favicon/generate_favicon.py
#   python3 tools/dev/favicon/generate_favicon.py --preview-dir /tmp/favicon --ascii
#
# Note that Sling-Initial-Content will not overwrite a `/favicon.ico` or `/favicon.svg` node
# that already exists, so a running instance keeps serving the old icons: start with a fresh
# data directory, or post the files over the existing nodes, to see the change.

import argparse
import math
import os
import struct
import zlib

CONTENT_DIR = os.path.join("modules", "favicon", "src", "main", "resources", "SLING-INF", "content")
DEFAULT_TARGET = os.path.join(CONTENT_DIR, "favicon.ico")
DEFAULT_SVG_TARGET = os.path.join(CONTENT_DIR, "favicon.svg")

# Brand colours, shared with the logo: navy Q, crimson P (the logo's token red). A favicon cannot
# tell a light tab strip from a dark one (`prefers-color-scheme` follows the system, not the
# browser's theme), and navy vanishes on a dark one, so the mark sits on a white keyline instead.
# The keyline is mostly transparent: on a light tab it all but disappears, and on a dark one it
# reads as a soft grey rather than a glaring white.
NAVY = (0x19, 0x29, 0x58)
RED = (0xC0, 0x23, 0x3C)
WHITE = (0xFF, 0xFF, 0xFF)
KEYLINE_OPACITY = 0.33

SIZES = (16, 32, 48)
SVG_SIZE = 48

# The mark, in design units. The Q's ring and the P's disc share a centre.
DESIGN = {
    "center_x": 19.5,
    "center_y": 24.0,
    "q_r_out": 15.5,      # outer radius of the Q's ring; the baseline is center_y + q_r_out
    "q_stroke": 2.5,      # weight of the Q's ring, and the height of its tail
    "tail": 19.0,         # length of the Q's tail, measured from the centre line
    "gap": 3.0,           # clear space between the P's disc and the inside of the Q's ring
    "stem": 6.0,          # width of the P's stem; its left edge is tangent to the disc
    "foot_radius": 3.0,   # rounding of the foot's outer corner, tangent to the stem and the baseline
}

# Ink extent in design units: from the Q's left edge to the end of its tail, and from the top of
# the Q to the baseline. The mark is scaled so this box fits between the margins.
INK_LEFT = DESIGN["center_x"] - DESIGN["q_r_out"]
INK_RIGHT = DESIGN["center_x"] + DESIGN["tail"]
INK_TOP = DESIGN["center_y"] - DESIGN["q_r_out"]
INK_BOTTOM = DESIGN["center_y"] + DESIGN["q_r_out"]

# Per-size placement and weights, all optional except margin and keyline.
#   margin   : border kept at the left and right edges, in device pixels; the mark is scaled so
#              its width fills the rest, and centred
#   keyline  : width of the white keyline round the mark, in device pixels; it must fit in the
#              margin, less the half pixel the mark may be shifted by (see below)
#   scale    : device pixels per design unit, overriding the margin-derived scale, for sizes where
#              the Q's radius has to land on a whole or half pixel
#   q_stroke : the Q's weight, in design units, if it is tuned at this size
#   gap      : the disc-to-ring gap, in design units, if it is tuned at this size; the P's disc
#              takes whatever radius is left inside the ring and the gap
# After scaling, the whole mark is shifted by under half a pixel so that the baseline and the
# P stem's left edge, the long straight edges, land on pixel boundaries, and the tail is trimmed
# or extended by under half a pixel so that its end does too; see layout().
#
# 16px is hand-hinted. At the design weights the ring would be under a pixel wide and the gap
# under 1.2, so both blur and the tab shows a red dot in a smudge: the recording indicator. Instead
# the Q's outer radius is exactly 6.5 pixels about a pixel-centred point, and the ring and the gap
# are each exactly one pixel, so the tab shows a crisp navy ring, a clean pixel of clearance and a
# 9-pixel disc. Keep both at a pixel or more if this is retuned. That leaves exactly one pixel
# round the mark for the keyline, with no room for the half-pixel shift: the hinting puts every
# edge on a pixel boundary already.
HINT_16 = 6.5 / DESIGN["q_r_out"]
GEOMETRY = {
    16: {"margin": 1.0, "keyline": 1.0, "scale": HINT_16, "q_stroke": 1.0 / HINT_16, "gap": 1.0 / HINT_16},
    32: {"margin": 2.0, "keyline": 1.5},
    48: {"margin": 3.5, "keyline": 3.0},
}

# Samples per axis per pixel, i.e. 64 coverage samples with the default. Cheap enough at these
# sizes, and a power of two keeps the coverage steps even.
SUPERSAMPLING = 8


def layout(size):
    # Turn a GEOMETRY entry into the scale and offset that map design units onto device pixels,
    # plus the weights to draw with. Shared by the rasterizer and the .svg writer so the two
    # renderings cannot disagree.
    geometry = GEOMETRY[size]
    scale = geometry.get("scale", (size - 2.0 * geometry["margin"]) / (INK_RIGHT - INK_LEFT))
    q_stroke = geometry.get("q_stroke", DESIGN["q_stroke"])
    gap = geometry.get("gap", DESIGN["gap"])
    p_radius = DESIGN["q_r_out"] - q_stroke - gap
    offset_x = size / 2.0 - (INK_LEFT + INK_RIGHT) / 2.0 * scale
    offset_y = size / 2.0 - (INK_TOP + INK_BOTTOM) / 2.0 * scale
    # Snap the two long straight edges: a baseline or stem edge that straddles a pixel renders as
    # a soft half-coverage row or column, which at 16px is a large share of the stroke.
    stem_left = (DESIGN["center_x"] - p_radius) * scale + offset_x
    baseline = INK_BOTTOM * scale + offset_y
    offset_x += round(stem_left) - stem_left
    offset_y += round(baseline) - baseline
    # The tail's square end is the mark's rightmost edge: trim or extend the tail to end it on a pixel boundary too.
    tail_end = (DESIGN["center_x"] + DESIGN["tail"]) * scale + offset_x
    tail = (round(tail_end) - offset_x) / scale - DESIGN["center_x"]
    return {"scale": scale, "offset_x": offset_x, "offset_y": offset_y,
            "q_stroke": q_stroke, "p_radius": p_radius, "tail": tail,
            "keyline": geometry["keyline"] / scale}


def keyline_box(p_radius, tail, keyline):
    # The keyline's outline along the baseline: a box round the P's foot and the Q's tail, grown by
    # the keyline's width. Its bottom left corner follows the foot's rounding, the others are
    # rounded by the keyline's width; above it, the keyline is a disc round the Q's ring.
    cx, baseline = DESIGN["center_x"], DESIGN["center_y"] + DESIGN["q_r_out"]
    return {"left": cx - p_radius - keyline, "right": cx + tail + keyline,
            "top": baseline - DESIGN["q_stroke"] - keyline, "bottom": baseline + keyline,
            "foot": DESIGN["foot_radius"] + keyline, "corner": keyline}


def classify(u, v, q_stroke, p_radius, tail, keyline):
    # Which ink covers the design-space point (u, v): "red", "navy", "white" or None. The P is
    # drawn over the Q, and both over the keyline; deciding per sample, rather than compositing
    # anti-aliased layers, keeps the seams between them free of a blended fringe.
    cx, cy = DESIGN["center_x"], DESIGN["center_y"]
    q_r_out, foot = DESIGN["q_r_out"], DESIGN["foot_radius"]
    baseline = cy + q_r_out
    dx, dy = u - cx, v - cy
    distance2 = dx * dx + dy * dy
    stem_left = cx - p_radius
    stem_right = stem_left + DESIGN["stem"]
    if distance2 <= p_radius ** 2:
        return "red"
    if cy <= v <= baseline:
        in_stem = stem_left <= u <= stem_right
        # The half serif: the notch between the stem and the Q's outer curve, above the baseline.
        in_serif = stem_right < u <= cx and distance2 >= q_r_out ** 2
        # The foot's outer corner is rounded off by a circle tangent to the stem and the baseline.
        corner_x, corner_y = stem_left + foot, baseline - foot
        in_cut = u < corner_x and v > corner_y and (u - corner_x) ** 2 + (v - corner_y) ** 2 > foot ** 2
        if (in_stem or in_serif) and not in_cut:
            return "red"
    if (q_r_out - q_stroke) ** 2 <= distance2 <= q_r_out ** 2:
        return "navy"
    if cx <= u <= cx + tail and baseline - q_stroke <= v <= baseline:
        return "navy"
    if distance2 <= (q_r_out + keyline) ** 2:
        return "white"
    box = keyline_box(p_radius, tail, keyline)
    if box["left"] <= u <= box["right"] and box["top"] <= v <= box["bottom"]:
        corners = ((box["left"], box["top"], box["corner"]), (box["right"], box["top"], box["corner"]),
                   (box["right"], box["bottom"], box["corner"]), (box["left"], box["bottom"], box["foot"]))
        for corner_x, corner_y, radius in corners:
            # The centre of this corner's rounding, inset from the corner by its radius.
            round_x = corner_x + radius if corner_x == box["left"] else corner_x - radius
            round_y = corner_y + radius if corner_y == box["top"] else corner_y - radius
            beyond_x = u < round_x if corner_x == box["left"] else u > round_x
            beyond_y = v < round_y if corner_y == box["top"] else v > round_y
            if beyond_x and beyond_y and (u - round_x) ** 2 + (v - round_y) ** 2 > radius ** 2:
                return None
        return "white"
    return None


def render(size):
    # Rasterize the mark into a size x size grid of straight-alpha RGBA tuples, by point-sampling
    # the shapes on a SUPERSAMPLING x SUPERSAMPLING grid inside every pixel.
    placement = layout(size)
    scale, offset_x, offset_y = placement["scale"], placement["offset_x"], placement["offset_y"]
    q_stroke, p_radius, tail = placement["q_stroke"], placement["p_radius"], placement["tail"]
    keyline = placement["keyline"]
    step = 1.0 / SUPERSAMPLING
    samples = float(SUPERSAMPLING * SUPERSAMPLING)
    image = []
    for pixel_y in range(size):
        row = []
        for pixel_x in range(size):
            hits = {"red": 0, "navy": 0, "white": 0}
            for sub_y in range(SUPERSAMPLING):
                v = (pixel_y + (sub_y + 0.5) * step - offset_y) / scale
                for sub_x in range(SUPERSAMPLING):
                    u = (pixel_x + (sub_x + 0.5) * step - offset_x) / scale
                    ink = classify(u, v, q_stroke, p_radius, tail, keyline)
                    if ink:
                        hits[ink] += 1
            weights = (hits["red"], hits["navy"], hits["white"] * KEYLINE_OPACITY)
            covered = sum(weights)
            if not covered:
                row.append((0, 0, 0, 0))
                continue
            # Straight alpha: the colour is the mix of the inks present, weighted by coverage and
            # opacity, and the alpha is their total.
            colour = tuple(
                int(round(sum(weight * channel for weight, channel in zip(weights, channels)) / covered))
                for channels in zip(RED, NAVY, WHITE))
            row.append(colour + (int(round(covered / samples * 255)),))
        image.append(row)
    return image


def write_ico(path, images):
    # An .ico is a small directory followed by one BITMAPINFOHEADER DIB per size, each holding
    # bottom-up BGRA rows and a 1bpp AND mask.
    directory = b""
    blobs = b""
    offset = 6 + 16 * len(images)
    for image in images:
        size = len(image)
        # Doubled height, as the DIB nominally covers both the colour data and the mask.
        header = struct.pack("<IiiHHIIiiII", 40, size, size * 2, 1, 32, 0, size * size * 4, 0, 0, 0, 0)
        pixels = bytearray()
        for y in range(size - 1, -1, -1):
            for red, green, blue, alpha in image[y]:
                pixels += bytes(bytearray((blue, green, red, alpha)))
        # The AND mask is set where the icon is fully transparent, bottom-up, most significant
        # bit first, rows padded to 4 bytes. 32bpp renderers use the alpha channel and ignore
        # it, but legacy Windows paths still read it.
        stride = ((size + 31) // 32) * 4
        mask = bytearray()
        for y in range(size - 1, -1, -1):
            row = bytearray(stride)
            for x, pixel in enumerate(image[y]):
                if pixel[3] == 0:
                    row[x // 8] |= 0x80 >> (x % 8)
            mask += row
        blob = header + bytes(pixels) + bytes(mask)
        directory += struct.pack("<BBBBHHII", size, size, 0, 0, 1, 32, len(blob), offset)
        blobs += blob
        offset += len(blob)
    with open(path, "wb") as icon_file:
        icon_file.write(struct.pack("<HHH", 0, 1, len(images)) + directory + blobs)


def hex_colour(colour):
    return "#{:02X}{:02X}{:02X}".format(*colour)


def number(value):
    # Trim trailing zeros so the markup stays readable: 24.0 becomes "24", 8.15 stays "8.15".
    text = "{:.3f}".format(value).rstrip("0").rstrip(".")
    return text or "0"


# The same Apache header the other .svg sources carry, since Apache RAT checks this output too.
SVG_LICENSE = """<!--
   Copyright 2026 DATA @ UHN. See the NOTICE file
   distributed with this work for additional information
   regarding copyright ownership.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->"""

# Colours and geometry are presentation attributes on the shapes, and there is no stylesheet at
# all: no colour-scheme or size-dependent media queries, and no CSS geometry
# (`d: path()`, `cx`, `r`), which only some browsers honour. One artwork serves every size; the
# .ico carries the hand-tuned 16px rendering for browsers that prefer it.
SVG_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
{license}
<svg width="{size}" height="{size}" viewBox="0 0 {size} {size}" xmlns="http://www.w3.org/2000/svg" role="img">
  <title>QuorumPath</title>
  <path fill="{white}" fill-opacity="{keyline_opacity}" d="{keyline}"/>
  <path fill="{navy}" d="{q}"/>
  <path fill="{red}" d="{p}"/>
</svg>
"""


def svg_markup():
    # The vector twin of the raster icon, drawn from the same placement: the keyline, the Q (ring
    # and tail) over it, then the P (disc, stem, half serif and rounded foot). All are emitted as
    # filled outlines under the non-zero rule, so each is a single filled path.
    placement = layout(SVG_SIZE)
    scale, offset_x, offset_y = placement["scale"], placement["offset_x"], placement["offset_y"]
    q_stroke, p_radius, tail = placement["q_stroke"], placement["p_radius"], placement["tail"]
    keyline = placement["keyline"]

    def x(u):
        return number(u * scale + offset_x)

    def y(v):
        return number(v * scale + offset_y)

    def length(d):
        return number(d * scale)

    def circle(u, v, r, clockwise):
        # A full circle as two half arcs, starting at its leftmost point. An outer edge runs
        # clockwise and the hole inside it the other way; the bars run clockwise too, which lets
        # them overlap a ring or the disc without punching through it.
        sweep = 1 if clockwise else 0
        return "M {x0} {y0} A {r} {r} 0 1 {s} {x1} {y0} A {r} {r} 0 1 {s} {x0} {y0} Z".format(
            x0=x(u - r), x1=x(u + r), y0=y(v), r=length(r), s=sweep)

    cx, cy = DESIGN["center_x"], DESIGN["center_y"]
    q_r_out, foot = DESIGN["q_r_out"], DESIGN["foot_radius"]
    baseline = cy + q_r_out

    q_path = " ".join((
        circle(cx, cy, q_r_out, True),
        circle(cx, cy, q_r_out - q_stroke, False),
        "M {} {} H {} V {} H {} Z".format(x(cx), y(baseline - q_stroke), x(cx + tail), y(baseline), x(cx)),
    ))

    # The stem, half serif and foot are one outline: down the stem's right edge to the Q's outer
    # curve, along that curve to the Q's lowest point, back left along the baseline, round the
    # foot's corner and up the stem's left edge.
    stem_left = cx - p_radius
    stem_right = stem_left + DESIGN["stem"]
    curve_y = cy + math.sqrt(q_r_out ** 2 - (cx - stem_right) ** 2)
    stem = ("M {l} {top} H {r} V {cy_} A {rad} {rad} 0 0 0 {cx_} {base} H {fx} "
            "A {f} {f} 0 0 1 {l} {fy} Z").format(
        l=x(stem_left), r=x(stem_right), top=y(cy), cy_=y(curve_y), rad=length(q_r_out),
        cx_=x(cx), base=y(baseline), fx=x(stem_left + foot), f=length(foot), fy=y(baseline - foot))
    p_path = " ".join((circle(cx, cy, p_radius, True), stem))

    # The keyline is the disc round the ring plus the rounded box along the baseline, clockwise.
    box = keyline_box(p_radius, tail, keyline)
    corner, foot_corner = length(box["corner"]), length(box["foot"])
    box_path = ("M {l_} {t} H {r_} A {c} {c} 0 0 1 {r} {t_} V {b_} A {c} {c} 0 0 1 {r_} {b} H {fl} "
                "A {f} {f} 0 0 1 {l} {fb} V {t_} A {c} {c} 0 0 1 {l_} {t} Z").format(
        l=x(box["left"]), r=x(box["right"]), t=y(box["top"]), b=y(box["bottom"]),
        l_=x(box["left"] + box["corner"]), r_=x(box["right"] - box["corner"]),
        t_=y(box["top"] + box["corner"]), b_=y(box["bottom"] - box["corner"]),
        fl=x(box["left"] + box["foot"]), fb=y(box["bottom"] - box["foot"]), c=corner, f=foot_corner)
    keyline_path = " ".join((circle(cx, cy, q_r_out + keyline, True), box_path))

    return SVG_TEMPLATE.format(
        license=SVG_LICENSE, size=SVG_SIZE,
        white=hex_colour(WHITE), keyline_opacity=number(KEYLINE_OPACITY), navy=hex_colour(NAVY),
        red=hex_colour(RED), keyline=keyline_path, q=q_path, p=p_path)


def write_svg(path):
    with open(path, "w") as svg_file:
        svg_file.write(svg_markup())


def write_png(path, image, scale=1):
    # Minimal RGBA PNG writer, for previews only: nearest-neighbour scaled so individual
    # pixels stay legible when eyeballing the result.
    size = len(image)
    raw = bytearray()
    for y in range(size * scale):
        raw.append(0)  # filter type 0 (None) for every scanline
        for x in range(size * scale):
            raw += bytes(bytearray(image[y // scale][x // scale]))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size * scale, size * scale, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as png_file:
        png_file.write(png)


def ascii_map(image):
    # A terminal-readable rendering: uppercase for (nearly) opaque pixels, lowercase for
    # anti-aliased edges, R for the crimson P, N for the navy Q, and w for the keyline, which is
    # never opaque. Handy for checking that the ring, the gap and the baseline land on whole pixels.
    lines = []
    for y, row in enumerate(image):
        line = ""
        for red, green, _blue, alpha in row:
            if alpha < 10:
                line += "."
            elif green > 150:
                line += "w"
            elif red > 120:
                line += "R" if alpha > 200 else "r"
            else:
                line += "N" if alpha > 200 else "n"
        if line.strip("."):
            lines.append("{:2d} {}".format(y, line))
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Regenerate the QuorumPath favicons.")
    parser.add_argument("-o", "--out", default=DEFAULT_TARGET,
                        help="where to write the .ico (default: %(default)s, relative to the repository root)")
    parser.add_argument("--svg-out", default=DEFAULT_SVG_TARGET,
                        help="where to write the .svg (default: %(default)s, relative to the repository root)")
    parser.add_argument("--preview-dir", metavar="DIR",
                        help="also write scaled-up and actual-size PNG previews of each size into DIR")
    parser.add_argument("--ascii", action="store_true", help="print an ASCII pixel map of each size")
    arguments = parser.parse_args()

    images = [render(size) for size in SIZES]
    write_ico(arguments.out, images)
    print("Wrote {} ({})".format(arguments.out, ", ".join("{0}x{0}".format(size) for size in SIZES)))
    write_svg(arguments.svg_out)
    print("Wrote {} (vector)".format(arguments.svg_out))

    if arguments.preview_dir:
        if not os.path.isdir(arguments.preview_dir):
            os.makedirs(arguments.preview_dir)
        for image in images:
            size = len(image)
            write_png(os.path.join(arguments.preview_dir, "favicon-{}-scaled.png".format(size)), image, 384 // size)
            write_png(os.path.join(arguments.preview_dir, "favicon-{}.png".format(size)), image)
        print("Wrote previews into {}".format(arguments.preview_dir))

    if arguments.ascii:
        for image in images:
            print("\n{0}x{0}".format(len(image)))
            print(ascii_map(image))


if __name__ == "__main__":
    main()
