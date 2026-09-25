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
import { describe, expect, it } from "vitest";

import { findQuoteRects, pageSearchOrder, parsePdfSource, type PdfTextRun } from "@iap/submissions/pdfQuoteLocator";

const PDF = "/Submissions/demo-1/proposal/1/file/file.pdf";

function run(overrides: Partial<PdfTextRun> = {}): PdfTextRun {
  return {
    str: "Participants receive pembrolizumab 200 mg.",
    x: 72,
    y: 700,
    width: 200,
    height: 12,
    hasEOL: false,
    ...overrides,
  };
}

describe("parsePdfSource", () => {
  it("keeps a path that names no page", () => {
    expect(parsePdfSource(PDF)).toEqual({ url: PDF });
  });

  it("reads the page the link names and drops the fragment", () => {
    expect(parsePdfSource(`${PDF}#page=9`)).toEqual({ url: PDF, page: 9 });
  });

  it("ignores a page number that is not a page", () => {
    expect(parsePdfSource(`${PDF}#page=0`)).toEqual({ url: PDF });
    expect(parsePdfSource(`${PDF}#zoom=100`)).toEqual({ url: PDF });
  });
});

describe("pageSearchOrder", () => {
  it("looks at the cited page before the others", () => {
    expect(pageSearchOrder(4, 3)).toEqual([ 3, 1, 2, 4 ]);
  });

  it("starts at the first page when none was cited", () => {
    expect(pageSearchOrder(3)).toEqual([ 1, 2, 3 ]);
  });

  it("ignores a page the document does not have", () => {
    expect(pageSearchOrder(2, 9)).toEqual([ 1, 2 ]);
  });
});

describe("findQuoteRects", () => {
  it("marks the run that contains the quote", () => {
    const rects = findQuoteRects([ run() ], "pembrolizumab 200 mg");

    expect(rects).toHaveLength(1);
    expect(rects[0]?.x).toBeGreaterThan(72);
    expect(rects[0]?.width).toBeLessThan(200);
    expect(rects[0]?.y).toBeLessThan(700);
  });

  it("matches across a line break and a change of case", () => {
    const rects = findQuoteRects([
      run({ str: "Participants receive", width: 100, hasEOL: true }),
      run({ str: "pembrolizumab 200 mg.", x: 72, y: 680, width: 120 }),
    ], "PARTICIPANTS RECEIVE PEMBROLIZUMAB");

    expect(rects).toHaveLength(2);
  });

  it("joins a word that was hyphenated at the line break", () => {
    const rects = findQuoteRects([
      run({ str: "pembrolizum-", width: 80, hasEOL: true }),
      run({ str: "ab 200 mg.", x: 72, y: 680, width: 70 }),
    ], "pembrolizumab 200 mg");

    expect(rects.length).toBeGreaterThan(0);
  });

  it("joins a word broken by a soft hyphen at the line break", () => {
    const rects = findQuoteRects([
      run({ str: "pembrolizum­", width: 80, hasEOL: true }),
      run({ str: "ab 200 mg.", x: 72, y: 680, width: 70 }),
    ], "pembrolizumab 200 mg");

    expect(rects.length).toBeGreaterThan(0);
  });

  it("still adds a space at the line break when PDF.js never reports hasEOL", () => {
    const rects = findQuoteRects([
      run({ str: "Participants receive", width: 100, hasEOL: false }),
      run({ str: "pembrolizumab 200 mg.", x: 72, y: 680, width: 120, hasEOL: false }),
    ], "Participants receive pembrolizumab");

    expect(rects).toHaveLength(2);
  });

  it("marks nothing when the page does not contain the quote", () => {
    expect(findQuoteRects([ run() ], "Symptom diaries may be completed electronically.")).toEqual([]);
  });

  it("marks nothing for a quote too short to trust", () => {
    expect(findQuoteRects([ run({ str: "Yes." }) ], "Yes")).toEqual([]);
  });
});

describe("findQuoteRects, against text the server matched", () => {
  // Docling straightens typographic quotes and dashes, and the quote was checked against its text
  it("finds a quote the PDF prints with curly quotes and long dashes", () => {
    const page = [ run({ str: "The participant\u2019s data \u2014 all of it \u2014 stays on site." }) ];

    expect(findQuoteRects(page, "The participant's data - all of it - stays on site.")).toHaveLength(1);
  });

  // A dash at the end of a line is usually a word broken over it, but not always
  it("finds a quote whose line ends in a real dash", () => {
    const page = [
      run({ str: "Consent is taken in two steps -", hasEOL: true }),
      run({ str: "first by phone.", y: 680 }),
    ];

    expect(findQuoteRects(page, "Consent is taken in two steps - first by phone.")).toHaveLength(2);
  });

  // The server forgives a slip, and a quote can run over a page break; the half that is here is marked
  it("marks the half of a quote that is on the page", () => {
    const page = [ run({ str: "Participants receive pembrolizumab every three weeks for a year." }) ];

    expect(findQuoteRects(page,
      "Participants receive pembrolizumab every three weeks, and a placebo arm gets saline.")).toHaveLength(1);
  });

  it("marks nothing when no long enough half is there", () => {
    const page = [ run({ str: "Participants receive pembrolizumab." }) ];

    expect(findQuoteRects(page, "Nobody receives anything at all in this study whatsoever.")).toEqual([]);
    expect(findQuoteRects(page, "Unrelatedword")).toEqual([]);
  });
});

describe("findQuoteRects, on awkward text", () => {
  // A soft hyphen breaks a word only where the line does; elsewhere it is not there at all
  it("reads past soft hyphens and leading spaces", () => {
    const page = [ run({ str: "  Partici\u00adpants receive treat\u00ad", hasEOL: true }), run({ str: "ment daily.", y: 680 }) ];

    expect(findQuoteRects(page, "participants receive treatment daily")).toHaveLength(2);
  });

  // A line with no height of its own is still told apart from the next by how far down the next one is
  it("tells lines apart when a run reports no height", () => {
    const page = [ run({ str: "Consent is taken", height: 0 }), run({ str: "by phone.", y: 680 }) ];

    expect(findQuoteRects(page, "consent is taken by phone")).toHaveLength(2);
  });

  it("assumes a usual font size when a run reports none", () => {
    const [ rect ] = findQuoteRects([ run({ height: 0 }) ], "Participants receive pembrolizumab");

    expect(rect?.height).toBe(12);
  });
});
