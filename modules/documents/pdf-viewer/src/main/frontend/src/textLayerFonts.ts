/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Sets a page's text layer in the fonts the page is drawn with, so that selecting, searching and marking land
// on the characters the reader sees.
//
// PDF.js sets its text layer in a generic font, `sans-serif`, `serif` or `monospace`, and stretches each piece
// of text to the width the page draws it. Only the two ends of a piece are then in place. A character inside
// sits where the generic font puts it, up to tens of pixels from its glyph where narrow and wide letters mix.
// PDF.js has already loaded the page's own fonts to draw it, named as its text content names each piece's
// font. This sets each piece in that font instead.
//
// The loaded font draws each glyph from a private code, but PDF.js also maps the character the PDF says a glyph
// stands for to that glyph. The piece's text therefore finds the glyphs the page draws. A character the font
// lacks, such as either letter of a ligature, falls back to the generic font.
//
// This relies on PDF.js internals: that mapping, one span per piece of text in order, and the loaded font's
// name being the piece's `fontName`. Where a span or a font is missing, the text is left as PDF.js laid it out,
// so the worst case is PDF.js's own. A real-browser test checks a known PDF, so a PDF.js upgrade that changes
// these internals fails it.

// The pieces of text PDF.js draws in a text layer, one per piece of the page's text content that has text.
// The role sets them apart from the spans PDF.js also puts there: marked content around them, and search
// highlights inside them.
const TEXT_SPANS = "span[role=\"presentation\"]";

// What setting the text in the page's fonts needs of the browser, kept apart to be tested without one.
export interface PageFonts {
  // The names of the fonts loaded into the page.
  loaded: ReadonlySet<string>;
  // How wide a text is in a CSS font family, at one size used throughout.
  measure: (text: string, family: string) => number;
}

// The CSS font family that sets a piece in its loaded font, with the generic one after it for any character
// the font lacks.
function getFamily(fontName: string, generic: string): string {
  return `"${fontName}", ${generic}`;
}

// A font family's name without the quotes around it. Browsers drop them from a name that needs none.
function unquote(family: string): string {
  return family.replace(/^"(.*)"$/, "$1");
}

// Whether a span is already set in a font: its font family names that font first.
function isSetIn(span: HTMLElement, fontName: string): boolean {
  return unquote(span.style.fontFamily.split(",")[0].trim()) === fontName;
}

// Sets each piece of a text layer in its loaded font, given the font of each piece in order, and keeps the
// piece's width. PDF.js measures a piece in its own font whenever it lays the layer out again, as after a
// zoom, so this is done once per piece. Returns whether any piece changed.
export function alignTextLayer(textLayer: HTMLElement, fontNames: string[], fonts: PageFonts): boolean {
  const spans = Array.from(textLayer.querySelectorAll<HTMLElement>(TEXT_SPANS));
  if (spans.length !== fontNames.length) {
    return false;
  }
  let changed = false;
  spans.forEach((span, index) => {
    const fontName = fontNames[index];
    const text = span.textContent;
    const generic = span.style.fontFamily;
    if (!fonts.loaded.has(fontName) || isSetIn(span, fontName)) {
      return;
    }
    const family = getFamily(fontName, generic);
    const genericWidth = fonts.measure(text, generic);
    const loadedWidth = fonts.measure(text, family);
    if (loadedWidth <= 0) {
      return;
    }
    const scale = Number(span.style.getPropertyValue("--scale-x") || 1);
    span.style.fontFamily = family;
    // Without it, Linux hinting rounds each letter of a loaded font to whole pixels, which undoes the fix.
    span.style.textRendering = "geometricPrecision";
    span.style.setProperty("--scale-x", String(scale * genericWidth / loadedWidth));
    changed = true;
  });
  return changed;
}

// The size text is measured at. Large, so that rounding does not count.
const MEASURE_SIZE = 1000;

// The browser's loaded fonts and a canvas to measure text with, or nothing where the browser offers neither,
// as in tests.
export function readPageFonts(): PageFonts | undefined {
  const context = typeof document.fonts === "undefined" ? null : document.createElement("canvas").getContext("2d");
  if (context === null) {
    return undefined;
  }
  const loaded = new Set(Array.from(document.fonts)
    .filter(face => face.status === "loaded")
    .map(face => unquote(face.family)));
  return {
    loaded,
    measure: (text, family) => {
      context.font = `${MEASURE_SIZE}px ${family}`;
      return context.measureText(text).width;
    },
  };
}
