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
import type { PageText } from "@iap/frontend-commons/pdfViewer/marksModel";
import {
  compareRevisions,
  describeChange,
  describeHidden,
  getChangeMarks,
  getChangeType,
  getWordKey,
  getWords,
  mapPoint,
  type Comparison,
} from "@iap/frontend-commons/pdfViewer/revisionDiffModel";

// Pages as PDF.js reads them: a line per item, with no character where one ends.
function pages(...texts: string[]): PageText[] {
  return texts.map(text => {
    const lineEnds: number[] = [];
    let joined = "";
    for (const line of text.split("\n")) {
      joined += line;
      lineEnds.push(joined.length);
    }
    return { text: joined, lineEnds: lineEnds.slice(0, -1) };
  });
}

async function compare(older: PageText[], newer: PageText[], strict = false) {
  const comparison = await compareRevisions(older, newer, { strict });
  if (comparison.status !== "compared") {
    throw new Error(`Not compared: ${comparison.status}`);
  }
  return comparison;
}

describe("getWords", () => {
  it("breaks the text at spaces and at the ends of lines, keeping where each word is on its page", () => {
    expect(getWords(pages("The fee\nis due", "today."))).toEqual([
      { page: 1, start: 0, end: 3, text: "The" },
      { page: 1, start: 4, end: 7, text: "fee" },
      { page: 1, start: 7, end: 9, text: "is" },
      { page: 1, start: 10, end: 13, text: "due" },
      { page: 2, start: 0, end: 6, text: "today." },
    ]);
  });

  it("runs a word broken by a hyphen at the end of a line on to the next line, without the hyphen", () => {
    expect(getWords(pages("infor-\nmation, 2026-\n10"))).toEqual([
      { page: 1, start: 0, end: 13, text: "information," },
      { page: 1, start: 14, end: 19, text: "2026-" },
      { page: 1, start: 19, end: 21, text: "10" },
    ]);
  });
});

describe("getWordKey", () => {
  const isSame = (one: string, other: string, strict = false) => getWordKey(one, strict) === getWordKey(other, strict);

  it("leaves case and punctuation out of the default comparison", () => {
    expect(isSame("Hello,", "hello")).toBe(true);
    expect(isSame("don't", "dont")).toBe(true);
    expect(isSame("“Fee”", "fee")).toBe(true);
    expect(isSame("fee", "fees")).toBe(false);
  });

  it("compares a number exactly, its separators and its sign too, but not the punctuation around it", () => {
    expect(isSame("1,000", "1.000")).toBe(false);
    expect(isSame("$26.72", "$2672")).toBe(false);
    expect(isSame("-5", "5")).toBe(false);
    expect(isSame("5%", "5")).toBe(false);
    expect(isSame("(−5).", "-5")).toBe(true);
    expect(isSame("1,000,", "1,000")).toBe(true);
  });

  it("leaves punctuation alone out of the default comparison", () => {
    expect(getWordKey("—", false)).toBeUndefined();
    expect(getWordKey("—", true)).toBe("—");
  });

  it("compares every character strictly, but a ligature as the letters it joins", () => {
    expect(isSame("Hello,", "hello,", true)).toBe(false);
    expect(isSame("ﬁle", "file", true)).toBe(true);
    expect(isSame("co\u00adoperate", "cooperate", true)).toBe(true);
  });
});

describe("compareRevisions", () => {
  it("finds no change between the same texts, however their lines and pages break", async () => {
    const older = pages("The fee is due within thirty days of the start of the study.");
    const newer = pages("The fee is\ndue with-\nin thirty days", "of the  start of the study.");

    expect(await compare(older, newer)).toMatchObject({ status: "compared", changes: [], hidden: 0 });
  });

  it("marks added words in the newer version, and the point they would go in the older", async () => {
    const { changes } = await compare(pages("The fee is due."), pages("The fee is now due."));

    expect(changes).toEqual([ {
      id: "change-1",
      removed: "",
      added: "now",
      older: [ { page: 1, start: 10, end: 10 } ],
      newer: [ { page: 1, start: 11, end: 14 } ],
    } ]);
    expect(getChangeType(changes[0])).toBe("added");
  });

  it("marks removed words in the older version, and the point they went from in the newer", async () => {
    const { changes } = await compare(pages("The fee is now due."), pages("The fee is due."));

    expect(changes).toMatchObject([ {
      removed: "now",
      added: "",
      older: [ { page: 1, start: 11, end: 14 } ],
      newer: [ { page: 1, start: 10, end: 10 } ],
    } ]);
    expect(getChangeType(changes[0])).toBe("removed");
  });

  it("puts a point at the start of a version with no word the comparison takes", async () => {
    const { changes } = await compare(pages("—"), pages("Fee"));

    expect(changes).toMatchObject([ { removed: "", added: "Fee", older: [ { page: 1, start: 0, end: 0 } ] } ]);
  });

  it("takes a lone soft hyphen as nothing", async () => {
    expect(await compare(pages("a \u00ad b"), pages("a b"))).toMatchObject({ status: "compared", changes: [], hidden: 0 });
  });

  it("puts the point of words removed at the start before the first word", async () => {
    const { changes } = await compare(pages("Draft. The fee is due."), pages("The fee is due."));

    expect(changes[0].newer).toEqual([ { page: 1, start: 0, end: 0 } ]);
  });

  it("marks words replaced in both versions, as one change", async () => {
    const { changes } = await compare(pages("The fee is 100 dollars."), pages("The fee is 120 Canadian dollars."));

    expect(changes).toMatchObject([ {
      removed: "100",
      added: "120 Canadian",
      older: [ { page: 1, start: 11, end: 14 } ],
      newer: [ { page: 1, start: 11, end: 23 } ],
    } ]);
    expect(getChangeType(changes[0])).toBe("replaced");
  });

  it("finds a change running over a page break once, as a span on each page", async () => {
    const { changes } = await compare(pages("One two three four", "five six seven"), pages("One two six seven"));

    expect(changes).toMatchObject([ {
      removed: "three four five",
      older: [ { page: 1, start: 8, end: 18 }, { page: 2, start: 0, end: 4 } ],
      newer: [ { page: 1, start: 7, end: 7 } ],
    } ]);
  });

  it("numbers the changes in the order of the document", async () => {
    const { changes } = await compare(pages("a b c d e"), pages("a x c d y"));

    expect(changes.map(({ id, removed, added }) => [ id, removed, added ])).toEqual([
      [ "change-1", "b", "x" ],
      [ "change-2", "e", "y" ],
    ]);
  });

  it("leaves out changes of case and punctuation by default, counting each run of them", async () => {
    const older = pages("Hello, world. The Fee is due — today.");
    const newer = pages("hello world The fee is due today.");

    expect(await compare(older, newer)).toMatchObject({ status: "compared", changes: [], hidden: 3 });
    expect((await compare(older, newer, true)).changes.map(({ removed, added }) => [ removed, added ])).toEqual([
      [ "Hello, world.", "hello world" ],
      [ "Fee", "fee" ],
      [ "—", "" ],
    ]);
  });

  it("counts no hidden change beside one it shows", async () => {
    expect(await compare(pages("The Fee is 100."), pages("The fee is 120."))).toMatchObject({ hidden: 1 });
    expect(await compare(pages("The fee is 100."), pages("The fee is 120"))).toMatchObject({ hidden: 0 });
    expect(await compare(pages("The fee is due ."), pages("The fee is due"))).toMatchObject({ hidden: 1 });
  });

  it("shows a change within a number", async () => {
    const { changes } = await compare(pages("The fee is $1,000."), pages("The fee is $1.000."));

    expect(changes.map(({ removed, added }) => [ removed, added ])).toEqual([ [ "$1,000.", "$1.000." ] ]);
  });

  it("says which version has no text, as a scan has none", async () => {
    const scan = pages("", " ");
    const text = pages("The fee is due.");

    expect(await compareRevisions(scan, text, { strict: false })).toEqual({ status: "noText", without: "older" });
    expect(await compareRevisions(text, scan, { strict: false })).toEqual({ status: "noText", without: "newer" });
    expect(await compareRevisions(scan, scan, { strict: false })).toEqual({ status: "noText", without: "both" });
  });

  it("gives up on versions that differ past its limits", async () => {
    const comparison: Comparison = await compareRevisions(pages("a b c d"), pages("w x y z"), {
      strict: false,
      limits: { timeout: 1000, maxEditLength: 2 },
    });

    expect(comparison).toEqual({ status: "tooDifferent" });
  });
});

describe("getChangeMarks", () => {
  const changes = [
    { id: "a", removed: "", added: "now", older: [ { page: 1, start: 4, end: 4 } ], newer: [ { page: 1, start: 4, end: 7 } ] },
    { id: "r", removed: "old", added: "", older: [ { page: 2, start: 0, end: 3 } ], newer: [ { page: 2, start: 0, end: 0 } ] },
    { id: "x", removed: "100", added: "120", older: [ { page: 3, start: 5, end: 8 } ], newer: [ { page: 3, start: 6, end: 9 } ] },
  ];

  it("marks removed text in the older version, and where added text would go", () => {
    expect(getChangeMarks(changes, "older", false)).toEqual([
      { id: "a", kind: "added", spans: [ { page: 1, start: 4, end: 4 } ] },
      { id: "r", kind: "removed", spans: [ { page: 2, start: 0, end: 3 } ] },
      { id: "x", kind: "removed", spans: [ { page: 3, start: 5, end: 8 } ] },
    ]);
  });

  it("marks added text in the newer version, and where removed text went from", () => {
    expect(getChangeMarks(changes, "newer", false)).toEqual([
      { id: "a", kind: "added", spans: [ { page: 1, start: 4, end: 7 } ] },
      { id: "r", kind: "removed", spans: [ { page: 2, start: 0, end: 0 } ] },
      { id: "x", kind: "added", spans: [ { page: 3, start: 6, end: 9 } ] },
    ]);
  });

  it("marks where replaced text went from in the newer version shown alone", () => {
    expect(getChangeMarks(changes, "newer", true).at(-1)).toEqual({
      id: "x",
      kind: "added",
      spans: [ { page: 3, start: 6, end: 6, kind: "removed" }, { page: 3, start: 6, end: 9 } ],
    });
  });
});

describe("describeChange", () => {
  const change = (removed: string, added: string) => ({
    id: "c",
    removed,
    added,
    older: [ { page: 2, start: 0, end: 1 } ],
    newer: [ { page: 4, start: 0, end: 1 } ],
  });
  const among = { index: 3, count: 17 };

  it("says which change it is, what it did on which page of the newer version, and its text", () => {
    expect(describeChange(change("", "now"), among)).toBe("Change 3 of 17. Added on page 4: “now”.");
    expect(describeChange(change("old", ""), among)).toBe("Change 3 of 17. Removed on page 4: “old”.");
    expect(describeChange(change("100", "120"), among)).toBe("Change 3 of 17. Replaced on page 4: “100” with “120”.");
  });

  it("cuts long text short", () => {
    expect(describeChange(change("", `${"word ".repeat(30)}end`), among))
      .toBe(`Change 3 of 17. Added on page 4: “${"word ".repeat(24).trimEnd()}…”.`);
  });
});

describe("describeHidden", () => {
  it("counts the changes left out", () => {
    expect(describeHidden(1)).toBe("1 change to punctuation or case hidden");
    expect(describeHidden(4)).toBe("4 changes to punctuation or case hidden");
  });
});

describe("mapPoint", () => {
  async function align(older: PageText[], newer: PageText[]) {
    return (await compare(older, newer)).alignment;
  }

  it("matches a word the versions share to the same word, on whichever page it is", async () => {
    const alignment = await align(pages("One two three four", "five six seven"), pages("One two six seven"));

    expect(mapPoint(alignment, "older", { page: 2, char: 6 })).toEqual({ page: 1, char: 8 });
    expect(mapPoint(alignment, "newer", { page: 1, char: 8 })).toEqual({ page: 2, char: 5 });
    expect(mapPoint(alignment, "newer", { page: 1, char: 0 })).toEqual({ page: 1, char: 0 });
  });

  it("matches a word only one version has to where its change sits in the other", async () => {
    const alignment = await align(pages("One two three four", "five six seven"), pages("One two six seven"));

    expect(mapPoint(alignment, "older", { page: 1, char: 9 })).toEqual({ page: 1, char: 8 });
    expect(mapPoint(alignment, "older", { page: 2, char: 0 })).toEqual({ page: 1, char: 8 });
  });

  it("matches the start of a version, before any word the versions share, to the start of the other", async () => {
    const alignment = await align(pages("  Draft. The fee is due."), pages("The fee is due."));

    expect(mapPoint(alignment, "older", { page: 1, char: 0 })).toEqual({ page: 1, char: 0 });
    expect(mapPoint(alignment, "older", { page: 1, char: 4 })).toEqual({ page: 1, char: 0 });
  });

  it("matches the end of a version to the last word of the other", async () => {
    const alignment = await align(pages("The fee is due today."), pages("The fee is due."));

    expect(mapPoint(alignment, "older", { page: 1, char: 16 })).toEqual({ page: 1, char: 11 });
  });

  it("matches anything to the start of a version without words", () => {
    const alignment = { older: getWords(pages("Fee")), newer: [], pairs: [] };

    expect(mapPoint(alignment, "older", { page: 1, char: 0 })).toEqual({ page: 1, char: 0 });
  });
});
