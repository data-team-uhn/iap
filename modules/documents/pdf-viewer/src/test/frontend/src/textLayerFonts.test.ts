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
// jsdom has neither loaded fonts nor a canvas to measure with, so the fonts are given: a loaded font twice as
// wide as the generic one.
import { alignTextLayer, readPageFonts, type PageFonts } from "@iap/documents-pdf-viewer/textLayerFonts";

const FONTS: PageFonts = {
  loaded: new Set([ "g_d0_f1", "g_d0_f2" ]),
  measure: (text, family) => text.length * (family.startsWith("\"g_") ? 20 : 10),
};

// A text layer as PDF.js draws one: a span per piece of text, in a generic font, some inside marked content.
function createTextLayer(...texts: string[]): HTMLElement {
  const textLayer = document.createElement("div");
  const marked = document.createElement("span");
  marked.className = "markedContent";
  textLayer.append(marked);
  texts.forEach((text, index) => {
    const span = document.createElement("span");
    span.textContent = text;
    span.setAttribute("role", "presentation");
    span.style.fontFamily = "sans-serif";
    span.style.setProperty("--scale-x", "0.8");
    (index === 0 ? marked : textLayer).append(span);
  });
  return textLayer;
}

const getSpans = (textLayer: HTMLElement) => Array.from(textLayer.querySelectorAll<HTMLElement>("span[role=\"presentation\"]"));

// Each span's font family without quotes, which the browser may drop.
const getFamilies = (textLayer: HTMLElement) => getSpans(textLayer).map(span => span.style.fontFamily.replaceAll("\"", ""));

describe("alignTextLayer", () => {
  it("sets each piece in its loaded font, keeping its width", () => {
    const textLayer = createTextLayer("iiiixmmmm", "Total");

    expect(alignTextLayer(textLayer, [ "g_d0_f1", "g_d0_f2" ], FONTS)).toBe(true);

    expect(getFamilies(textLayer)).toEqual([ "g_d0_f1, sans-serif", "g_d0_f2, sans-serif" ]);
    const [ first ] = getSpans(textLayer);
    // Twice as wide in its own font, so stretched half as much
    expect(first.style.getPropertyValue("--scale-x")).toBe("0.4");
    expect(first.style.textRendering.toLowerCase()).toBe("geometricprecision");
  });

  it("counts the pieces of text, not the search highlights PDF.js draws inside them", () => {
    const textLayer = createTextLayer("Total");
    const highlight = document.createElement("span");
    highlight.className = "highlight appended";
    getSpans(textLayer)[0].append(highlight);

    expect(alignTextLayer(textLayer, [ "g_d0_f1" ], FONTS)).toBe(true);
  });

  it("leaves a piece whose font is not loaded as it is", () => {
    const textLayer = createTextLayer("Total", "Grand total");

    alignTextLayer(textLayer, [ "g_d0_f1", "g_d0_f9" ], FONTS);

    expect(getFamilies(textLayer)).toEqual([ "g_d0_f1, sans-serif", "sans-serif" ]);
  });

  it("leaves every piece as it is when the pieces do not match the fonts given", () => {
    const textLayer = createTextLayer("Total", "Grand total");

    expect(alignTextLayer(textLayer, [ "g_d0_f1" ], FONTS)).toBe(false);

    expect(getFamilies(textLayer)).toEqual([ "sans-serif", "sans-serif" ]);
  });

  it("sets a piece in its font once, however often it is asked", () => {
    const textLayer = createTextLayer("Total");
    alignTextLayer(textLayer, [ "g_d0_f1" ], FONTS);

    expect(alignTextLayer(textLayer, [ "g_d0_f1" ], FONTS)).toBe(false);

    expect(getSpans(textLayer)[0].style.getPropertyValue("--scale-x")).toBe("0.4");
  });

  it("leaves a piece that measures nothing in its loaded font, such as an empty one, as it is", () => {
    const textLayer = createTextLayer("", "Total");

    alignTextLayer(textLayer, [ "g_d0_f1", "g_d0_f1" ], FONTS);

    expect(getFamilies(textLayer)).toEqual([ "sans-serif", "g_d0_f1, sans-serif" ]);
  });

  it("stretches a piece without a scale of its own from 1", () => {
    const textLayer = createTextLayer("Total");
    getSpans(textLayer)[0].style.removeProperty("--scale-x");

    alignTextLayer(textLayer, [ "g_d0_f1" ], FONTS);

    expect(getSpans(textLayer)[0].style.getPropertyValue("--scale-x")).toBe("0.5");
  });
});

describe("readPageFonts", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    Reflect.deleteProperty(document, "fonts");
  });

  it("gives nothing where the browser has no loaded fonts", () => {
    expect(readPageFonts()).toBeUndefined();
  });

  it("gives nothing where the browser cannot measure text", () => {
    Object.defineProperty(document, "fonts", { configurable: true, value: [] });
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);

    expect(readPageFonts()).toBeUndefined();
  });

  it("gives the names of the fonts loaded, and measures text in a font", () => {
    Object.defineProperty(document, "fonts", {
      configurable: true,
      value: [
        { family: "\"g_d0_f1\"", status: "loaded" },
        { family: "g_d0_f2", status: "loaded" },
        { family: "g_d0_f3", status: "loading" },
      ],
    });
    const context = { font: "", measureText: (text: string) => ({ width: text.length * 7 }) };
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(context as unknown as CanvasRenderingContext2D);

    const fonts = readPageFonts();

    expect([ ...fonts?.loaded ?? [] ]).toEqual([ "g_d0_f1", "g_d0_f2" ]);
    expect(fonts?.measure("Total", "\"g_d0_f1\", sans-serif")).toBe(35);
    expect(context.font).toBe("1000px \"g_d0_f1\", sans-serif");
  });
});
