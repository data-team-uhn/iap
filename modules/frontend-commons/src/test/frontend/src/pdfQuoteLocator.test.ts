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
import {
  findQuoteRects,
  getPageSearchOrder,
  getSearchText,
  parsePdfSource,
  type PdfTextRun,
} from "@iap/frontend-commons/pdfQuoteLocator";

const PDF = "/Submissions/demo-1/proposal/1/file/file.pdf";

function createRun(overrides: Partial<PdfTextRun> = {}): PdfTextRun {
  return {
    str: "Participants receive pembrolizumab 200 mg.",
    x: 72,
    y: 700,
    width: 200,
    height: 12,
    ...overrides,
  };
}

function findQuote(runs: PdfTextRun[], quote: string) {
  const searchText = getSearchText(quote);
  if (searchText === undefined) {
    throw new Error(`Too short to search for: ${quote}`);
  }
  return findQuoteRects(runs, searchText);
}

describe("parsePdfSource", () => {
  it("keeps a path that names no page", () => {
    expect(parsePdfSource(PDF)).toEqual({ url: PDF });
  });

  it("reads the page the link names and drops the fragment", () => {
    expect(parsePdfSource(`${PDF}#page=9`)).toEqual({ url: PDF, page: 9 });
  });

  it("reads the page whatever the case of the parameter", () => {
    expect(parsePdfSource(`${PDF}#Page=4`)).toEqual({ url: PDF, page: 4 });
  });

  it("ignores a page number that is not a page", () => {
    expect(parsePdfSource(`${PDF}#page=0`)).toEqual({ url: PDF });
    expect(parsePdfSource(`${PDF}#zoom=100`)).toEqual({ url: PDF });
  });
});

describe("getPageSearchOrder", () => {
  it("looks at the cited page first, then at the pages nearest to it", () => {
    expect(getPageSearchOrder(4, 3)).toEqual([ 3, 4, 2, 1 ]);
    expect(getPageSearchOrder(6, 2)).toEqual([ 2, 3, 1, 4, 5, 6 ]);
  });

  it("starts at the first page when none was cited", () => {
    expect(getPageSearchOrder(3)).toEqual([ 1, 2, 3 ]);
  });

  it("ignores a page the document does not have", () => {
    expect(getPageSearchOrder(2, 9)).toEqual([ 1, 2 ]);
  });
});

describe("getSearchText", () => {
  it("leaves out spaces and case", () => {
    expect(getSearchText("Pembrolizumab  200 MG")).toBe("pembrolizumab200mg");
  });

  it("gives nothing for a quote too short to trust", () => {
    expect(getSearchText("Yes")).toBeUndefined();
  });
});

describe("findQuoteRects", () => {
  it("marks the run that contains the quote", () => {
    const rects = findQuote([ createRun() ], "pembrolizumab 200 mg");

    expect(rects).toHaveLength(1);
    expect(rects[0]?.x).toBeGreaterThan(72);
    expect(rects[0]?.width).toBeLessThan(200);
    expect(rects[0]?.y).toBeLessThan(700);
  });

  it("matches across a line break and a change of case", () => {
    const rects = findQuote([
      createRun({ str: "Participants receive", width: 100 }),
      createRun({ str: "pembrolizumab 200 mg.", x: 72, y: 680, width: 120 }),
    ], "PARTICIPANTS RECEIVE PEMBROLIZUMAB");

    expect(rects).toHaveLength(2);
  });

  it("matches when PDF.js left out the space between two runs", () => {
    const rects = findQuote([
      createRun({ str: "Participants receive", width: 100 }),
      createRun({ str: "pembrolizumab 200 mg.", x: 175, width: 120 }),
    ], "Participants receive pembrolizumab");

    expect(rects).toHaveLength(2);
  });

  it("joins a word that was hyphenated at the line break", () => {
    const rects = findQuote([
      createRun({ str: "pembrolizum-", width: 80 }),
      createRun({ str: "ab 200 mg.", x: 72, y: 680, width: 70 }),
    ], "pembrolizumab 200 mg");

    expect(rects).toHaveLength(2);
  });

  it("joins a word broken by a soft hyphen at the line break", () => {
    const rects = findQuote([
      createRun({ str: "pembrolizum­", width: 80 }),
      createRun({ str: "ab 200 mg.", x: 72, y: 680, width: 70 }),
    ], "pembrolizumab 200 mg");

    expect(rects).toHaveLength(2);
  });

  it("matches a hyphenated word that is also broken at the line break", () => {
    const rects = findQuote([
      createRun({ str: "an evidence-", width: 80 }),
      createRun({ str: "based dose.", x: 72, y: 680, width: 70 }),
    ], "an evidence-based dose");

    expect(rects).toHaveLength(2);
  });

  it("matches a quote with both a hyphenated word and a word split at a line end", () => {
    const rects = findQuote([
      createRun({ str: "an evidence-", width: 80 }),
      createRun({ str: "based pembrolizum-", x: 72, y: 680, width: 110 }),
      createRun({ str: "ab dosing.", x: 72, y: 660, width: 70 }),
    ], "evidence-based pembrolizumab dosing");

    expect(rects).toHaveLength(3);
  });

  it("matches a dash that ends a line", () => {
    const rects = findQuote([
      createRun({ str: "200 mg -", width: 60 }),
      createRun({ str: "daily", x: 72, y: 680, width: 40 }),
    ], "200 mg - daily");

    expect(rects).toHaveLength(2);
  });

  it("tells a negative number from a positive one", () => {
    const str = "a change of 5 mm in group A and a change of -5 mm in group B";
    const rects = findQuote([ createRun({ str, width: 400 }) ], "a change of −5 mm");

    expect(rects[0]?.x).toBeCloseTo(72 + 400 * (str.indexOf("a change of -5") / str.length));
  });

  it("matches a superscript on the same line", () => {
    const rects = findQuote([
      createRun({ str: "a dose of 200 mg/m", width: 120 }),
      createRun({ str: "2", x: 192, y: 705, width: 5, height: 7 }),
      createRun({ str: " daily.", x: 197, width: 40 }),
    ], "200 mg/m2 daily");

    expect(rects).toHaveLength(3);
  });

  it("matches a micro sign against the Greek mu PDF.js writes", () => {
    const rects = findQuote([ createRun({ str: "a dose of 2 μg/kg." }) ], "dose of 2 µg/kg");

    expect(rects).toHaveLength(1);
  });

  it("matches typographic quotes and dashes against plain ones", () => {
    const rects = findQuote([
      createRun({ str: "The patient’s dose – 200 mg – is “fixed”." }),
    ], "the patient's dose - 200 mg - is \"fixed\"");

    expect(rects).toHaveLength(1);
  });

  it("matches an accent drawn as its own character against one typed with its letter", () => {
    const rects = findQuote([ createRun({ str: "The café is open." }) ], "the café is open");

    expect(rects).toHaveLength(1);
  });

  it("matches an accent typed as its own character against one drawn with its letter", () => {
    const rects = findQuote([ createRun({ str: "The café is open." }) ], "the café is open");

    expect(rects).toHaveLength(1);
  });

  it("marks the right characters after a letter with an accent", () => {
    const str = "İstanbul pembrolizumab 200 mg.";
    const rects = findQuote([ createRun({ str, width: 300 }) ], "pembrolizumab");

    expect(rects[0]?.x).toBeCloseTo(72 + 300 * (str.indexOf("pembrolizumab") / str.length));
  });

  it("marks nothing when the page does not contain the quote", () => {
    expect(findQuote([ createRun() ], "Symptom diaries may be completed electronically.")).toEqual([]);
  });
});
