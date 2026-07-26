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

# Regenerates the QuorumPath favicon shipped by the `favicon` module. The mark is a navy
# "path" running from the left edge into the centre of a red token (a filled disc with a
# translucent halo ring) sitting at the end of the path — the small-size counterpart of the
# application logo in `modules/homepage/.../media/default/logo-light.svg`.
#
# The favicon is not a scaled copy of that logo: at 16-48 pixels the logo's hairlines
# disappear, so weights are tuned per size (see GEOMETRY) and the artwork is rasterized here
# rather than converted from the SVG. Everything below is the standard library only — no
# Pillow, no ImageMagick — because a 32bpp .ico is just bottom-up BMP data behind a small
# directory, and the mark is two circles and a thick segment, which anti-alias cleanly from
# supersampled coverage.
#
# Run from the repository root:
#   python3 tools/dev/favicon/generate_favicon.py
#   python3 tools/dev/favicon/generate_favicon.py --preview-dir /tmp/favicon --ascii
#
# Note that Sling-Initial-Content will not overwrite a `/favicon.ico` node that already
# exists, so a running instance keeps serving the old icon: start with a fresh data
# directory, or post the file over the existing node, to see the change.

import argparse
import os
import struct
import zlib

DEFAULT_TARGET = os.path.join("modules", "favicon", "src", "main", "resources", "SLING-INF", "content", "favicon.ico")

# Brand colours, shared with the logo: navy path, red token, and the same 45% halo opacity.
NAVY = (0x19, 0x29, 0x58)
RED = (0xC0, 0x23, 0x3C)
RING_OPACITY = 0.45

SIZES = (16, 32, 48)

# Per-size geometry, in device pixels. The mark is always centred on cy = size / 2, an exact
# pixel boundary, so an even path thickness lands squarely on two rows instead of straddling
# four at half coverage.
#   t      : path thickness
#   r_out  : outer radius of the halo ring; 0 disables the halo
#   stroke : halo ring stroke width
#   r_dot  : radius of the filled token
#   margin : ink-free border kept at the left and right edges
# At 16px the halo is dropped: a sub-pixel ring at 45% opacity only muddies the token at that
# size, so the token alone carries the mark, pixel-snapped (r 3 about cy 8) to stay round.
GEOMETRY = {
    16: {"t": 2.0, "r_out": 0.0, "stroke": 0.00, "r_dot": 3.00, "margin": 1.0},
    32: {"t": 2.0, "r_out": 6.1, "stroke": 1.05, "r_dot": 3.55, "margin": 1.4},
    48: {"t": 2.6, "r_out": 8.8, "stroke": 1.30, "r_dot": 5.20, "margin": 2.0},
}

# Samples per axis per pixel, i.e. 64 coverage samples with the default. Cheap enough at these
# sizes, and a power of two keeps the coverage steps even.
SUPERSAMPLING = 8


def coverage_masks(size):
    # Compute per-pixel coverage in [0, 1] for each of the three shapes, by point-sampling
    # their analytic definitions on a SUPERSAMPLING x SUPERSAMPLING grid inside every pixel.
    geometry = GEOMETRY[size]
    thickness, r_out, stroke, r_dot, margin = (
        geometry["t"], geometry["r_out"], geometry["stroke"], geometry["r_dot"], geometry["margin"],
    )
    center_y = size / 2.0
    # The widest ink around the token — halo if there is one, otherwise the disc — sets the inset.
    center_x = size - margin - max(r_out, r_dot)
    # The path stops at the token's centre, and its round cap keeps the left ink at the margin.
    path_start = margin + thickness / 2.0
    half_thickness = thickness / 2.0
    r_in = r_out - stroke

    path = [[0.0] * size for _ in range(size)]
    ring = [[0.0] * size for _ in range(size)]
    dot = [[0.0] * size for _ in range(size)]
    step = 1.0 / SUPERSAMPLING
    samples = float(SUPERSAMPLING * SUPERSAMPLING)
    for pixel_y in range(size):
        for pixel_x in range(size):
            in_path = in_ring = in_dot = 0
            for sub_y in range(SUPERSAMPLING):
                y = pixel_y + (sub_y + 0.5) * step
                dy = y - center_y
                for sub_x in range(SUPERSAMPLING):
                    x = pixel_x + (sub_x + 0.5) * step
                    # Distance to the segment (path_start, cy)-(center_x, cy), giving round caps.
                    nearest_x = min(max(x, path_start), center_x)
                    if (x - nearest_x) ** 2 + dy * dy <= half_thickness ** 2:
                        in_path += 1
                    distance2 = (x - center_x) ** 2 + dy * dy
                    if distance2 <= r_dot ** 2:
                        in_dot += 1
                    elif r_in ** 2 <= distance2 <= r_out ** 2:
                        in_ring += 1
            path[pixel_y][pixel_x] = in_path / samples
            ring[pixel_y][pixel_x] = in_ring / samples
            dot[pixel_y][pixel_x] = in_dot / samples
    return path, ring, dot


def render(size):
    # Composite the mark into a size x size grid of straight-alpha RGBA tuples.
    path, ring, dot = coverage_masks(size)
    image = []
    for y in range(size):
        row = []
        for x in range(size):
            # The halo goes down first: since the path now runs all the way to the token's
            # centre it crosses the halo's left arc, and keeping the halo underneath leaves the
            # path reading as one unbroken line. The token always sits on top.
            layers = ((RED, ring[y][x] * RING_OPACITY), (NAVY, path[y][x]), (RED, dot[y][x]))
            red = green = blue = alpha = 0.0
            for (source_red, source_green, source_blue), source_alpha in layers:
                if source_alpha <= 0:
                    continue
                out_alpha = source_alpha + alpha * (1 - source_alpha)
                red = (source_red * source_alpha + red * alpha * (1 - source_alpha)) / out_alpha
                green = (source_green * source_alpha + green * alpha * (1 - source_alpha)) / out_alpha
                blue = (source_blue * source_alpha + blue * alpha * (1 - source_alpha)) / out_alpha
                alpha = out_alpha
            row.append((int(round(red)), int(round(green)), int(round(blue)), int(round(alpha * 255))))
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
    # anti-aliased edges, R for the red token or halo, N for the navy path. Handy for checking
    # that the path and the token land on whole pixels.
    lines = []
    for y, row in enumerate(image):
        line = ""
        for red, _green, _blue, alpha in row:
            if alpha < 10:
                line += "."
            elif red > 120:
                line += "R" if alpha > 200 else "r"
            else:
                line += "N" if alpha > 200 else "n"
        if line.strip("."):
            lines.append("{:2d} {}".format(y, line))
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Regenerate the QuorumPath favicon.")
    parser.add_argument("-o", "--out", default=DEFAULT_TARGET,
                        help="where to write the .ico (default: %(default)s, relative to the repository root)")
    parser.add_argument("--preview-dir", metavar="DIR",
                        help="also write scaled-up and actual-size PNG previews of each size into DIR")
    parser.add_argument("--ascii", action="store_true", help="print an ASCII pixel map of each size")
    arguments = parser.parse_args()

    images = [render(size) for size in SIZES]
    write_ico(arguments.out, images)
    print("Wrote {} ({})".format(arguments.out, ", ".join("{0}x{0}".format(size) for size in SIZES)))

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
