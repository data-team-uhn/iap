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
import { getMarksKey, MarkLocator, orderMarks, type Mark } from "@iap/frontend-commons/pdfViewer/marksModel";

const QUOTE = "Volunteers who, on a casual basis, assist from time to time.";

function mark(page?: number, quote = QUOTE): Mark {
  return { id: "m", quote, page, kind: "assistant" };
}

// The place of the one mark, after reading the pages given, by page number.
function placeAfter(pages: Record<number, string>, pageCount: number, given = mark()) {
  const locator = new MarkLocator([ given ], pageCount);
  for (const [ page, text ] of Object.entries(pages)) {
    locator.addPage(Number(page), text);
  }
  return locator.getPlaces().m;
}

describe("MarkLocator", () => {
  it("does not look for a passage too short to find", () => {
    expect(placeAfter({}, 2, mark(1, "Yes"))).toEqual({ status: "tooShort" });
  });

  it("is still looking before any page is read, even before the document is open", () => {
    expect(new MarkLocator([ mark() ]).getPlaces().m).toEqual({ status: "searching" });
    expect(placeAfter({}, 3)).toEqual({ status: "searching" });
  });

  it("places a passage on the page it is on, over the offsets it covers in the page's text", () => {
    expect(placeAfter({ 1: "Cover.", 2: `The memo. ${QUOTE}` }, 3))
      .toEqual({ status: "found", page: 2, spans: [ { page: 2, start: 10, end: 10 + QUOTE.length } ] });
  });

  it("places a passage on its first occurrence on a page", () => {
    expect(placeAfter({ 1: `${QUOTE} Again: ${QUOTE}` }, 1))
      .toEqual({ status: "found", page: 1, spans: [ { page: 1, start: 0, end: QUOTE.length } ] });
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

    expect(place).toMatchObject({ status: "found", page: 2, toPage: 3 });
    const spans = place?.status === "found" ? place.spans : [];
    expect(spans.map(({ page, start, end }) => pages[page as 2 | 3].slice(start, end)))
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

describe("orderMarks", () => {
  const marks = [ "a", "b", "c", "d", "e" ].map(id => ({ ...mark(), id }));

  it("keeps the order given, with the passages that cannot be marked after the others", () => {
    const ordered = orderMarks(marks, {
      a: { status: "notFound" },
      b: { status: "found", page: 3, spans: [] },
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
});
