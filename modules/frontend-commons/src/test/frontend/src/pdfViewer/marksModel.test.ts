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
  getMarkPage,
  getMarksKey,
  getMarkText,
  getPassageText,
  getPlacedPages,
  MarkLocator,
  orderMarks,
  type Mark,
  type MarkSpan,
} from "@iap/frontend-commons/pdfViewer/marksModel";

const QUOTE = "Volunteers who, on a casual basis, assist from time to time.";

function mark(page?: number, quote = QUOTE): Mark {
  return { id: "m", quote, page, kind: "assistant" };
}

function placed(...spans: MarkSpan[]): Mark {
  return { id: "m", spans, kind: "annotation" };
}

// The place of the one mark, after reading the pages given, by page number, each line of a page ending
// where PDF.js says it does rather than with a character.
function placeAfter(pages: Record<number, string>, pageCount: number, given = mark()) {
  const locator = new MarkLocator([ given ], pageCount);
  for (const [ page, text ] of Object.entries(pages)) {
    const lines = text.split("\n");
    const lineEnds = lines.slice(0, -1).map((_, index) => lines.slice(0, index + 1).join("").length);
    locator.addPage(Number(page), lines.join(""), lineEnds);
  }
  return locator.getPlaces().m;
}

describe("getPassageText", () => {
  const page = { text: "The memo covers newvolunteers whoassist from time to time.", lineEnds: [ 19, 33 ] };

  it("reads a passage with a space where a line ends", () => {
    expect(getPassageText(page, 4, 33)).toBe("memo covers new volunteers who");
  });

  it("joins a word a hyphen breaks at a line end", () => {
    expect(getPassageText({ text: "a docu-ment here", lineEnds: [ 7 ] }, 2, 11)).toBe("docu-ment");
  });

  it("reads runs of spaces as one, with none around the passage", () => {
    expect(getPassageText({ text: " The   memo ", lineEnds: [] }, 0, 12)).toBe("The memo");
  });

  it("reads nothing at a point", () => {
    expect(getPassageText(page, 4, 4)).toBe("");
  });
});

describe("MarkLocator", () => {
  it("does not look for a passage too short to find", () => {
    expect(placeAfter({}, 2, mark(1, "Yes"))).toEqual({ status: "tooShort" });
  });

  it("is still looking before any page is read, even before the document is open", () => {
    expect(new MarkLocator([ mark() ]).getPlaces().m).toEqual({ status: "searching" });
    expect(placeAfter({}, 3)).toEqual({ status: "searching" });
  });

  it("places a passage on the page it is on, over the offsets it covers in the page's text", () => {
    expect(placeAfter({ 1: "Cover.", 2: `The memo. ${QUOTE}` }, 3)).toEqual({
      status: "found",
      page: 2,
      spans: [ { page: 2, start: 10, end: 10 + QUOTE.length } ],
      text: QUOTE,
    });
  });

  it("places a passage on its first occurrence on a page", () => {
    expect(placeAfter({ 1: `${QUOTE} Again: ${QUOTE}` }, 1))
      .toEqual({ status: "found", page: 1, spans: [ { page: 1, start: 0, end: QUOTE.length } ], text: QUOTE });
  });

  it("waits for the pages before it, in the order it looks", () => {
    expect(placeAfter({ 2: QUOTE }, 3)).toEqual({ status: "searching" });
    expect(placeAfter({ 3: QUOTE }, 3, mark(3))).toMatchObject({ status: "found", page: 3 });
  });

  it("looks from its own page to the end, then from the start", () => {
    const pages = { 1: QUOTE, 2: "Contents.", 3: QUOTE };
    expect(placeAfter(pages, 3, mark(2))).toMatchObject({ page: 3 });
    expect(placeAfter({ 1: QUOTE, 2: "Contents.", 3: "The end." }, 3, mark(2))).toMatchObject({ page: 1 });
    expect(placeAfter({ 1: QUOTE, 2: "Contents." }, 3, mark(2))).toEqual({ status: "searching" });
  });

  it("looks from the first page when its own is not in the document", () => {
    expect(placeAfter({ 1: QUOTE, 2: QUOTE }, 2, mark(9))).toMatchObject({ page: 1 });
  });

  it("places a passage broken across two pages over the break, once every page is read", () => {
    const pages = {
      1: "Cover page of the document.",
      2: "The memo covers new volunteers. Volunteers who, on a casual basis,\nPage 2 of 3",
      3: "Volunteer guidelines\nassist from time to time. They may require a check.",
    };
    expect(placeAfter({ 2: pages[2], 3: pages[3] }, 3)).toEqual({ status: "searching" });

    const place = placeAfter(pages, 3);

    expect(place).toMatchObject({ status: "found", page: 2, toPage: 3, text: QUOTE });
    const spans = place?.status === "found" ? place.spans : [];
    expect(spans.map(({ page, start, end }) => pages[page as 2 | 3].replace("\n", "").slice(start, end)))
      .toEqual([ "Volunteers who, on a casual basis,", "assist from time to time." ]);
  });

  it("prefers a page the passage is whole on to a break it fits across", () => {
    const pages = {
      1: "Notes. Volunteers who, on a casual basis,",
      2: "assist from time to time. More notes.",
      3: QUOTE,
    };
    expect(placeAfter(pages, 3)).toMatchObject({ page: 3, spans: [ { page: 3 } ] });
  });

  it("says when the passage is in no page", () => {
    expect(placeAfter({ 1: "Cover.", 2: "Contents." }, 2)).toEqual({ status: "notFound" });
  });

  it("says when the document has no text to search", () => {
    expect(placeAfter({ 1: "", 2: " \n" }, 2)).toEqual({ status: "noText" });
  });

  it("places each mark on its own", () => {
    const locator = new MarkLocator([ mark(), { id: "n", quote: "They may require a check.", kind: "annotation" } ], 2);
    locator.addPage(1, QUOTE);
    locator.addPage(2, "They may require a check.");

    expect(locator.getPlaces()).toMatchObject({ m: { page: 1 }, n: { page: 2 } });
  });
});

describe("MarkLocator, for a mark placed by its spans", () => {
  const PAGES = { 1: "Cover page.", 2: "The memo covers\nnew volunteers.", 3: "They may require a check." };

  it("places it where its spans say once their pages are read, with the text it covers", () => {
    expect(placeAfter({ 2: PAGES[2] }, 3, placed({ page: 2, start: 4, end: 29 }))).toEqual({
      status: "found",
      page: 2,
      spans: [ { page: 2, start: 4, end: 29 } ],
      text: "memo covers new volunteers",
    });
  });

  it("waits for the pages its spans are on, and those alone", () => {
    expect(new MarkLocator([ placed({ page: 2, start: 0, end: 3 }) ]).getPlaces().m).toEqual({ status: "searching" });
    expect(placeAfter({ 1: PAGES[1], 3: PAGES[3] }, 3, placed({ page: 2, start: 0, end: 3 })))
      .toEqual({ status: "searching" });
  });

  it("places it over every page it runs over, from the first to the last", () => {
    const spans = [ { page: 1, start: 6, end: 11 }, { page: 2, start: 0, end: 15 }, { page: 3, start: 0, end: 4 } ];
    const place = placeAfter(PAGES, 3, placed(...spans));

    expect(place).toMatchObject({ status: "found", page: 1, toPage: 3, text: "page. The memo covers They" });
  });

  it("places a point, which covers no text", () => {
    expect(placeAfter(PAGES, 3, placed({ page: 3, start: 5, end: 5 })))
      .toMatchObject({ status: "found", page: 3, text: "" });
  });

  it("says it is not in the document when its spans do not fit it", () => {
    expect(placeAfter(PAGES, 3, placed())).toEqual({ status: "notFound" });
    expect(placeAfter(PAGES, 3, placed({ page: 4, start: 0, end: 3 }))).toEqual({ status: "notFound" });
    expect(placeAfter(PAGES, 3, placed({ page: 0, start: 0, end: 3 }))).toEqual({ status: "notFound" });
    expect(placeAfter(PAGES, 3, placed({ page: 1, start: 5, end: 2 }))).toEqual({ status: "notFound" });
    expect(placeAfter(PAGES, 3, placed({ page: 1, start: -1, end: 2 }))).toEqual({ status: "notFound" });
    expect(placeAfter(PAGES, 3, placed({ page: 1, start: 0, end: 99 }))).toEqual({ status: "notFound" });
    expect(placeAfter(PAGES, 3, placed({ page: 1, start: 0.5, end: 2 }))).toEqual({ status: "notFound" });
  });

  it("is placed beside marks looked for by their quote", () => {
    const locator = new MarkLocator([ placed({ page: 1, start: 0, end: 5 }), { ...mark(), id: "q" } ], 2);
    locator.addPage(1, "Cover page.");
    locator.addPage(2, QUOTE);

    expect(locator.getPlaces()).toMatchObject({ m: { page: 1, text: "Cover" }, q: { page: 2 } });
  });
});

describe("getMarkPage", () => {
  it("is the page a quote is looked for from, or the first a mark placed by its spans is on", () => {
    expect(getMarkPage(mark(4))).toBe(4);
    expect(getMarkPage(mark())).toBeUndefined();
    expect(getMarkPage(placed({ page: 3, start: 0, end: 1 }, { page: 4, start: 0, end: 1 }))).toBe(3);
    expect(getMarkPage(placed())).toBeUndefined();
  });
});

describe("getMarkText", () => {
  it("is the quote given, without its quotation marks", () => {
    expect(getMarkText(mark(1, `“${QUOTE}”`), { status: "searching" })).toBe(QUOTE);
  });

  it("is the text found where a mark placed by its spans is, once it is placed", () => {
    const given = placed({ page: 1, start: 0, end: 5 });
    expect(getMarkText(given, { status: "searching" })).toBeUndefined();
    expect(getMarkText(given, { status: "found", page: 1, spans: [], text: "Cover" })).toBe("Cover");
  });
});

describe("getPlacedPages", () => {
  it("lists the pages marks placed by their spans are on, once each, in order", () => {
    const marks = [
      mark(5),
      placed({ page: 3, start: 0, end: 1 }, { page: 2, start: 0, end: 1 }),
      { ...placed({ page: 3, start: 4, end: 5 }), id: "n" },
    ];

    expect(getPlacedPages(marks)).toEqual([ 2, 3 ]);
  });
});

describe("orderMarks", () => {
  const marks = [ "a", "b", "c", "d", "e" ].map(id => ({ ...mark(), id }));

  it("keeps the order given, with the passages that cannot be marked after the others", () => {
    const ordered = orderMarks(marks, {
      a: { status: "notFound" },
      b: { status: "found", page: 3, spans: [], text: "" },
      c: { status: "tooShort" },
      d: { status: "searching" },
      e: { status: "noText" },
    });

    expect(ordered.map(({ id }) => id)).toEqual([ "b", "d", "a", "c", "e" ]);
  });

  it("keeps the order given while every passage is still looked for", () => {
    const searching = Object.fromEntries(marks.map(({ id }) => [ id, { status: "searching" } as const ]));
    expect(orderMarks(marks, searching).map(({ id }) => id)).toEqual([ "a", "b", "c", "d", "e" ]);
  });
});

describe("getMarksKey", () => {
  it("is the same for equal marks, and changes with what is marked", () => {
    const marks = [ mark(2), { id: "n", quote: "They may require a check.", kind: "annotation" as const } ];

    expect(getMarksKey(marks.map(each => ({ ...each })))).toBe(getMarksKey(marks));
    expect(getMarksKey([ { ...marks[0], page: 3 }, marks[1] ])).not.toBe(getMarksKey(marks));
  });

  it("changes with where a mark placed by its spans is, and not with anything else it carries", () => {
    const at = (end: number) => placed({ page: 1, start: 0, end });

    const carrying = { ...at(5), extra: true };

    expect(getMarksKey([ carrying ])).toBe(getMarksKey([ at(5) ]));
    expect(getMarksKey([ at(6) ])).not.toBe(getMarksKey([ at(5) ]));
  });
});
