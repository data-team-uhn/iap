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

  it("marks nothing when the page does not contain the quote", () => {
    expect(findQuoteRects([ run() ], "Symptom diaries may be completed electronically.")).toEqual([]);
  });

  it("marks nothing for a quote too short to trust", () => {
    expect(findQuoteRects([ run({ str: "Yes." }) ], "Yes")).toEqual([]);
  });
});
