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
import type { PageText, TextAnchor } from "@iap/frontend-commons/pdfViewer/marksModel";
import { decodeAnchor, encodeAnchor, getTextAnchor } from "@iap/frontend-commons/pdfViewer/textAnchor";

// Two pages, the first read as three lines, which run on in its text without a space.
const PAGES: Record<number, PageText> = {
  1: {
    text: "Memo to all principals and staff.The board asks for a Police Records Checkfrom every new volunteer.",
    lineEnds: [ 33, 74 ],
  },
  2: { text: "Volunteers who, on a casual basis, assist from time to time.", lineEnds: [] },
};
const getPage = (page: number) => PAGES[page];

describe("getTextAnchor", () => {
  it("keeps where a passage starts and ends, what it says as it reads, and the text around it", () => {
    const start = PAGES[1].text.indexOf("Police");

    expect(getTextAnchor([ { page: 1, start, end: 84 } ], getPage)).toEqual({
      start: { page: 1, char: start },
      end: { page: 1, char: 84 },
      quote: "Police Records Check from every",
      before: "and staff. The board asks for a",
      after: "new volunteer.",
    });
  });

  it("keeps at most 32 characters before and after the passage", () => {
    const anchor = getTextAnchor([ { page: 1, start: 33, end: 46 } ], getPage);

    expect(anchor.before).toBe("emo to all principals and staff.");
    expect(anchor.after).toBe("s for a Police Records Check fro");
  });

  it("runs from the first span's start to the last span's end, saying what every span covers", () => {
    const anchor = getTextAnchor([ { page: 1, start: 74, end: 99 }, { page: 2, start: 0, end: 15 } ], getPage);

    expect(anchor).toMatchObject({
      start: { page: 1, char: 74 },
      end: { page: 2, char: 15 },
      quote: "from every new volunteer. Volunteers who,",
      after: "on a casual basis, assist from t",
    });
  });
});

describe("encodeAnchor and decodeAnchor", () => {
  const ANCHOR: TextAnchor = {
    start: { page: 3, char: 120 },
    end: { page: 4, char: 45 },
    quote: "a well-known, 100% safe place",
    before: "Schools are",
    after: "for students.",
  };

  it("keeps where an anchor starts and ends as pages and offsets", () => {
    expect(encodeAnchor(ANCHOR)).toMatchObject({ start: "page=3&char=120", end: "page=4&char=45" });
  });

  it("keeps what it says, and the text around it, as a text fragment directive", () => {
    expect(encodeAnchor(ANCHOR).text)
      .toBe("Schools%20are-,a%20well%2Dknown%2C%20100%25%20safe%20place,-for%20students.");
    expect(encodeAnchor({ ...ANCHOR, before: "", after: "" }).text)
      .toBe("a%20well%2Dknown%2C%20100%25%20safe%20place");
  });

  it("reads back the anchor it kept", () => {
    expect(decodeAnchor(encodeAnchor(ANCHOR))).toEqual(ANCHOR);
    const bare = { ...ANCHOR, before: "", after: "" };
    expect(decodeAnchor(encodeAnchor(bare))).toEqual(bare);
    expect(decodeAnchor(encodeAnchor({ ...ANCHOR, after: "" }))).toEqual({ ...ANCHOR, after: "" });
  });

  it("reads none where none was kept, or one it cannot read", () => {
    const stored = encodeAnchor(ANCHOR);

    expect(decodeAnchor({})).toBeUndefined();
    expect(decodeAnchor({ ...stored, text: undefined })).toBeUndefined();
    expect(decodeAnchor({ ...stored, start: "page=0&char=1" })).toBeUndefined();
    expect(decodeAnchor({ ...stored, start: "page=3&char=-1" })).toBeUndefined();
    expect(decodeAnchor({ ...stored, start: "page=3" })).toBeUndefined();
    expect(decodeAnchor({ ...stored, start: "char=1" })).toBeUndefined();
    expect(decodeAnchor({ ...stored, end: "page=2.5&char=1" })).toBeUndefined();
    expect(decodeAnchor({ ...stored, text: "" })).toBeUndefined();
    expect(decodeAnchor({ ...stored, text: "a,b,c" })).toBeUndefined();
    expect(decodeAnchor({ ...stored, text: "a-,-b" })).toBeUndefined();
    expect(decodeAnchor({ ...stored, text: "%E0%A4%A" })).toBeUndefined();
  });

  it("reads none that ends before it starts", () => {
    const stored = encodeAnchor(ANCHOR);

    expect(decodeAnchor({ ...stored, end: "page=2&char=500" })).toBeUndefined();
    expect(decodeAnchor({ ...stored, end: "page=3&char=119" })).toBeUndefined();
    expect(decodeAnchor({ ...stored, end: "page=3&char=120" })).toMatchObject({ end: { page: 3, char: 120 } });
  });
});
