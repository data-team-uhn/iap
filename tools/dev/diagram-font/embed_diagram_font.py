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

# Embeds the Inter font into the documentation's SVG diagrams, so that their text looks the same,
# and fits the same, on every system. A diagram shown as an image loads nothing from outside itself,
# so a font can only reach it inside the file.
#
# Inter is subset to printable ASCII and a little typographic punctuation, at the text optical size,
# and keeps its copyright and SIL Open Font License notice in its own name table. Running the script
# again replaces the embedded copy, which is what to do after a label gains a character the subset
# lacks, or after a new Inter release.
#
# Needs fontTools and brotli (pip install fonttools brotli) and an unpacked Inter release, from
# https://github.com/rsms/inter/releases. Run from the repository root:
#   python3 tools/dev/diagram-font/embed_diagram_font.py <Inter release directory> [<svg>...]
# With no SVGs named, it embeds into every docs/images/bpmn-*.svg.

import base64
import glob
import io
import os
import re
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

CHARACTERS = "".join(chr(c) for c in range(0x20, 0x7F)) + " —–×•…‘’“”"

# Only what a browser turns on by itself: Inter's stylistic sets, character variants and fraction
# forms would render nowhere unless a diagram asked for them, and they are a third of its size
FEATURES = ["calt", "ccmp", "clig", "kern", "liga", "locl", "mark", "mkmk", "rlig", "rvrn"]

# The diagrams use 400 and 600 upright and 400 italic. At 11-13px a browser would choose the
# smallest optical size anyway, so it is fixed there rather than carried as an axis.
FACES = [
    ("InterVariable.woff2", {"opsz": 14, "wght": (400, 600)}, "font-weight: 400 600;"),
    ("InterVariable-Italic.woff2", {"opsz": 14, "wght": 400}, "font-style: italic; font-weight: 400;"),
]

EMBEDDED = re.compile(r"    /\* The text is set in Inter .*?\*/\n(    @font-face \{[^\n]*\}\n)+", re.S)


def build(path, axes):
    font = instancer.instantiateVariableFont(TTFont(path), axes)
    # keep the release's own timestamp, so the same release always embeds the same bytes
    font.recalcTimestamp = False
    options = subset.Options()
    options.flavor = "woff2"
    options.layout_features = FEATURES
    # the names, plus the copyright (0) and the license notice (13, 14) the OFL asks to travel along
    options.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14]
    options.hinting = False
    options.desubroutinize = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(text=CHARACTERS)
    subsetter.subset(font)
    out = io.BytesIO()
    subset.save_font(font, out, options)
    version = font["name"].getDebugName(5).split(";")[0].replace("Version ", "")
    return base64.b64encode(out.getvalue()).decode("ascii"), version


def block(release):
    faces = [(build(os.path.join(release, "web", name), axes), descriptors)
             for name, axes, descriptors in FACES]
    version = faces[0][0][1]
    lines = [
        f"    /* The text is set in Inter {version} (https://rsms.me/inter/), copyright 2016 The Inter",
        "       Project Authors, licensed under the SIL Open Font License 1.1, whose notice the font",
        "       carries. Embedded by tools/dev/diagram-font/embed_diagram_font.py, since an image loads",
        "       nothing from outside itself; run it again after a label gains a new character. */",
    ]
    for (data, _), descriptors in faces:
        lines.append(f"    @font-face {{ font-family: Inter; {descriptors} "
                     f"src: url(data:font/woff2;base64,{data}) format(\"woff2\"); }}")
    return "\n".join(lines) + "\n"


def embed(path, fonts):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    text = EMBEDDED.sub("", text)
    if text.count("  <style>\n") != 1:
        sys.exit(f"{path}: expected exactly one <style> block")
    text = text.replace("  <style>\n", "  <style>\n" + fonts, 1)
    # Inter first in every font stack; the system fonts stay behind it for anything it lacks
    text = re.sub(r"(font: [^;]*?)(?<!Inter, )-apple-system", r"\1Inter, -apple-system", text)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: embed_diagram_font.py <Inter release directory> [<svg>...]")
    svgs = sys.argv[2:] or sorted(glob.glob("docs/images/bpmn-*.svg"))
    if not svgs:
        sys.exit("no diagrams found; run it from the repository root")
    fonts = block(sys.argv[1])
    for path in svgs:
        embed(path, fonts)
        print(f"{path}: {os.path.getsize(path)} bytes")


if __name__ == "__main__":
    main()
