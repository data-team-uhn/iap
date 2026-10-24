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
import type { MarkSpan, PageText } from "@iap/frontend-commons/pdfViewer/marksModel";
import {
  compareRevisions,
  countMinorChanges,
  getChangeInContext,
  getChangeMarks,
  getChangeType,
  getShownChanges,
  getWordKey,
  getWords,
  joinChanges,
  mapPoint,
  type Comparison,
  type RevisionChange,
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

// Every change found between two versions.
async function compareAll(older: PageText[], newer: PageText[]) {
  const comparison = await compareRevisions(older, newer);
  if (comparison.status !== "compared") {
    throw new Error(`Not compared: ${comparison.status}`);
  }
  return comparison;
}

// The comparison as a viewer shows it, strictly or not: the changes shown, and how many minor ones there are.
async function compare(older: PageText[], newer: PageText[], strict = false) {
  const comparison = await compareAll(older, newer);
  return {
    ...comparison,
    changes: getShownChanges(comparison.changes, strict),
    minor: countMinorChanges(comparison.changes),
  };
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

  it("runs a word broken by a hyphen at the end of a page on to the next page", () => {
    expect(getWords(pages("All partici-", "pants agree."))).toEqual([
      { page: 1, start: 0, end: 3, text: "All" },
      { page: 1, start: 4, end: 12, text: "participants", next: { page: 2, end: 5 } },
      { page: 2, start: 6, end: 12, text: "agree." },
    ]);
  });
});

describe("getWordKey", () => {
  const isSame = (one: string, other: string, strict = false) => getWordKey(one, strict) === getWordKey(other, strict);

  it("leaves case and the punctuation around a word out of the default comparison", () => {
    expect(isSame("Hello,", "hello")).toBe(true);
    expect(isSame("“Fee”", "fee")).toBe(true);
    expect(isSame("fee", "fees")).toBe(false);
  });

  it("keeps an apostrophe within a word, of whichever shape, and leaves hyphens within it out", () => {
    expect(isSame("patient's", "patients")).toBe(false);
    expect(isSame("It's", "its")).toBe(false);
    expect(isSame("don’t", "don't")).toBe(true);
    expect(isSame("self-aware", "selfaware")).toBe(true);
    expect(isSame("pre–post", "pre-post")).toBe(true);
  });

  it("keeps a number's hyphens, of whichever shape, and its apostrophes", () => {
    expect(isSame("IL-6", "IL6")).toBe(false);
    expect(isSame("5–10", "5-10")).toBe(true);
    expect(isSame("1990’s", "1990's")).toBe(true);
  });

  it("compares a number exactly, its separators and its sign too, but not the punctuation around it", () => {
    expect(isSame("1,000", "1.000")).toBe(false);
    expect(isSame("$26.72", "$2672")).toBe(false);
    expect(isSame("-5", "5")).toBe(false);
    expect(isSame("5%", "5")).toBe(false);
    expect(isSame("(−5).", "-5")).toBe(true);
    expect(isSame("1,000,", "1,000")).toBe(true);
  });

  it("keys a number holding a very long run of punctuation quickly, as a hostile document can", () => {
    const word = `1${"!".repeat(100_000)}a`;
    const started = performance.now();

    expect(getWordKey(`(${word}).`, false)).toBe(word);
    expect(performance.now() - started).toBeLessThan(1000);
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

    expect(await compare(older, newer)).toMatchObject({ status: "compared", changes: [], minor: 0 });
  });

  it("marks added words in the newer version, and the point they would go in the older", async () => {
    const { changes } = await compare(pages("The fee is due."), pages("The fee is now due."));

    expect(changes).toEqual([ {
      id: "change-1",
      minor: false,
      removed: "",
      added: "now",
      older: [ { page: 1, start: 11, end: 11, starts: true } ],
      newer: [ { page: 1, start: 11, end: 14 } ],
      context: { older: { before: "The fee is", after: "due." }, newer: { before: "The fee is", after: "due." } },
    } ]);
    expect(getChangeType(changes[0])).toBe("added");
  });

  it("marks removed words in the older version, and the point they went from in the newer", async () => {
    const { changes } = await compare(pages("The fee is now due."), pages("The fee is due."));

    expect(changes).toMatchObject([ {
      removed: "now",
      added: "",
      older: [ { page: 1, start: 11, end: 14 } ],
      newer: [ { page: 1, start: 11, end: 11, starts: true } ],
    } ]);
    expect(getChangeType(changes[0])).toBe("removed");
  });

  it("takes punctuation alone as a word, replaced as any other is", async () => {
    const { changes } = await compare(pages("—"), pages("Fee"));

    expect(changes).toMatchObject([ { minor: false, removed: "—", added: "Fee", older: [ { page: 1, start: 0, end: 1 } ] } ]);
  });

  it("takes a lone soft hyphen as nothing", async () => {
    expect(await compare(pages("a \u00ad b"), pages("a b"))).toMatchObject({ status: "compared", changes: [], minor: 0 });
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

  it("finds no change in a word broken across a page break in one version only", async () => {
    const older = pages("All study participants agree.");
    const newer = pages("All study partici-", "pants agree.");

    expect(await compare(older, newer)).toMatchObject({ changes: [], minor: 0 });
  });

  it("marks a change to a word broken across a page break on both pages", async () => {
    const { changes } = await compare(pages("All study members agree."), pages("All study partici-", "pants agree."));

    expect(changes).toEqual([ expect.objectContaining({
      added: "partici-pants",
      newer: [ { page: 1, start: 10, end: 18 }, { page: 2, start: 0, end: 5 } ],
    }) ]);
  });

  it("puts the point after a word broken across a page break on the page it ends on", async () => {
    const { changes } = await compare(pages("All partici-", "pants agree."), pages("All participants. Now agree."));

    expect(changes.find(change => change.added === "Now")?.older).toEqual([ { page: 2, start: 6, end: 6, starts: true } ]);
  });

  it("puts the point of text removed with what came before it, if it ended a sentence, or with what follows", async () => {
    const ended = await compare(pages("Intro here. Gone now. Next."), pages("Intro here. Next."));
    const started = await compare(pages("Study protocol", "Draft version two"), pages("Study protocol", "Version two"));

    expect(ended.changes[0].newer).toEqual([ { page: 1, start: 11, end: 11 } ]);
    expect(started.changes.find(change => change.removed === "Draft")?.newer)
      .toEqual([ { page: 2, start: 0, end: 0, starts: true } ]);
  });

  it("finds a change running over a page break once, as a span on each page", async () => {
    const { changes } = await compare(pages("One two three four", "five six seven"), pages("One two six seven"));

    expect(changes).toMatchObject([ {
      removed: "three four five",
      older: [ { page: 1, start: 8, end: 18 }, { page: 2, start: 0, end: 4 } ],
      newer: [ { page: 1, start: 8, end: 8, starts: true } ],
    } ]);
  });

  it("numbers the changes in the order of the document", async () => {
    const { changes } = await compare(pages("a b c d e"), pages("a x c d y"));

    expect(changes.map(({ id, removed, added }) => [ id, removed, added ])).toEqual([
      [ "change-1", "b", "x" ],
      [ "change-2", "e", "y" ],
    ]);
  });

  it("leaves out changes of case and punctuation by default, counting them as a strict comparison finds them", async () => {
    const older = pages("Hello, world. The Fee is due — today.");
    const newer = pages("hello world The fee is due today.");

    expect(await compare(older, newer)).toMatchObject({ status: "compared", changes: [], minor: 3 });
    expect((await compare(older, newer, true)).changes.map(({ removed, added }) => [ removed, added ])).toEqual([
      [ "Hello, world.", "hello world" ],
      [ "Fee", "fee" ],
      [ "—", "" ],
    ]);
  });

  it("takes a sentence rewritten in words common in the text as one change, leaving the next sentence alone", async () => {
    const { changes } = await compare(
      pages("Intro here. Data site report record data. Site data report record."),
      pages("Intro here. Replaced in version two. Site data report record."),
    );

    expect(changes.map(({ removed, added }) => [ removed, added ]))
      .toEqual([ [ "Data site report record data.", "Replaced in version two." ] ]);
  });

  it("still finds the words that changed within a sentence", async () => {
    const { changes } = await compare(
      pages("Patients come from the outpatient clinic. Data is kept."),
      pages("Patients come from the sleep clinic. Data is kept."),
    );

    expect(changes.map(({ removed, added }) => [ removed, added ])).toEqual([ [ "outpatient", "sleep" ] ]);
  });

  it("counts as minor only the changes of punctuation or case alone", async () => {
    expect(await compare(pages("The Fee is 100."), pages("The fee is 120."))).toMatchObject({ minor: 1 });
    expect(await compare(pages("The fee is 100."), pages("The fee is 120"))).toMatchObject({ minor: 0 });
    expect(await compare(pages("The fee is due ."), pages("The fee is due"))).toMatchObject({ minor: 1 });
    expect(await compare(pages("The Fee 100 is due."), pages("The fee 120 is due.")))
      .toMatchObject({ minor: 1, changes: [ { removed: "100", added: "120" } ] });
  });

  it("leaves out the mark ending a sentence, or the capital starting one, that only moved with a change", async () => {
    const moved = async (older: string, newer: string) => (await compareAll(pages(older), pages(newer))).changes
      .map(({ removed, added, minor }) => [ removed, added, minor ]);

    expect(await moved("We agree. Then go.", "We agree fully. Then go.")).toEqual([ [ "", "fully.", false ] ]);
    expect(await moved("We agree fully. Then go.", "We agree. Then go.")).toEqual([ [ "fully.", "", false ] ]);
    expect(await moved("We agree. Then go.", "We agree. And then go.")).toEqual([ [ "", "And", false ] ]);
    expect(await moved("We Agree fully. Then go.", "We agree. Then go."))
      .toEqual([ [ "Agree", "agree.", true ], [ "fully.", "", false ] ]);
    expect(await moved("A b Fee. Next is.", "A c fee. Next is.")).toEqual([ [ "b", "c", false ], [ "Fee.", "fee.", true ] ]);
  });

  it("keeps a run of words differing in case or punctuation alone as one minor change", async () => {
    const { changes } = await compareAll(pages("A b Fee. Next is."), pages("A c fee. next is."));

    expect(changes.map(({ removed, added, minor }) => [ removed, added, minor ]))
      .toEqual([ [ "b", "c", false ], [ "Fee. Next", "fee. next", true ] ]);
  });

  it("numbers the changes the same whether minor ones are shown or not", async () => {
    const older = pages("The Fee is 100.");
    const newer = pages("The fee is 120.");

    expect((await compare(older, newer)).changes.map(({ id }) => id)).toEqual([ "change-2" ]);
    expect((await compare(older, newer, true)).changes.map(({ id }) => id)).toEqual([ "change-1", "change-2" ]);
  });

  it("shows a change within a number", async () => {
    const { changes } = await compare(pages("The fee is $1,000."), pages("The fee is $1.000."));

    expect(changes.map(({ removed, added }) => [ removed, added ])).toEqual([ [ "$1,000.", "$1.000." ] ]);
  });

  it("says which version has no text, as a scan has none", async () => {
    const scan = pages("", " ");
    const text = pages("The fee is due.");

    expect(await compareRevisions(scan, text)).toEqual({ status: "noText", without: "older" });
    expect(await compareRevisions(text, scan)).toEqual({ status: "noText", without: "newer" });
    expect(await compareRevisions(scan, scan)).toEqual({ status: "noText", without: "both" });
  });

  it("gives up on versions that differ past its limits", async () => {
    const comparison: Comparison = await compareRevisions(pages("a b c d"), pages("w x y z"), {
      limits: { timeout: 1000, maxEditLength: 2 },
    });

    expect(comparison).toEqual({ status: "tooDifferent" });
  });

  it("gives up once out of time, all the diffs it takes counted together", async () => {
    expect(await compareRevisions(pages("a b c"), pages("a x c"), { limits: { timeout: 0, maxEditLength: 100 } }))
      .toEqual({ status: "tooDifferent" });
  });

  it("counts the words of whole sentences added or removed against its limits", async () => {
    const limits = { timeout: 1000, maxEditLength: 5 };

    expect(await compareRevisions(pages("Kept here. A b c. D e f."), pages("Kept here."), { limits }))
      .toEqual({ status: "tooDifferent" });
    expect(await compareRevisions(pages("Kept here. A b c."), pages("Kept here."), { limits }))
      .toMatchObject({ status: "compared" });
  });

  it("gives up on a very long passage added, rather than failing", async () => {
    const added = Array.from({ length: 3000 }, (_, index) => `Sentence ${index} goes on for a while here.`).join(" ");

    expect(await compareRevisions(pages("Kept here."), pages(`Kept here. ${added}`)))
      .toEqual({ status: "tooDifferent" });
  });
});

// A change with the text and places given, and no words around it.
function change(removed: string, added: string, older: MarkSpan[], newer: MarkSpan[], id = "c"): RevisionChange {
  const context = { before: "", after: "" };
  return { id, minor: false, removed, added, older, newer, context: { older: context, newer: context } };
}

describe("joinChanges", () => {
  it("joins two changes and the few shared words between them, no more than half of either", () => {
    expect(joinChanges([
      { removed: true, count: 6 },
      { count: 2 },
      { removed: true, count: 4 },
      { added: true, count: 1 },
      { count: 9 },
    ])).toEqual([ { removed: true, count: 12 }, { added: true, count: 3 }, { count: 9 } ]);
  });

  it("keeps apart small changes a word apart, and changes apart further than they are large", () => {
    const apart = [ { removed: true, count: 1 }, { added: true, count: 1 }, { count: 1 }, { removed: true, count: 1 } ];
    const far = [ { removed: true, count: 4 }, { count: 3 }, { added: true, count: 4 } ];

    expect(joinChanges(apart)).toEqual(apart);
    expect(joinChanges(far)).toEqual(far);
  });
});

describe("getChangeMarks", () => {
  const changes = [
    change("", "now", [ { page: 1, start: 4, end: 4 } ], [ { page: 1, start: 4, end: 7 } ], "a"),
    change("old", "", [ { page: 2, start: 0, end: 3 } ], [ { page: 2, start: 0, end: 0 } ], "r"),
    change("100", "120", [ { page: 3, start: 5, end: 8 } ], [ { page: 3, start: 6, end: 9 } ], "x"),
  ];

  it("marks removed text in the older version, and where added text would go", () => {
    expect(getChangeMarks(changes, "older")).toEqual([
      { id: "a", kind: "added", spans: [ { page: 1, start: 4, end: 4 } ] },
      { id: "r", kind: "removed", spans: [ { page: 2, start: 0, end: 3 } ] },
      { id: "x", kind: "removed", spans: [ { page: 3, start: 5, end: 8 } ] },
    ]);
  });

  it("marks added text in the newer version, where removed text went from, and where replacing text starts", () => {
    expect(getChangeMarks(changes, "newer")).toEqual([
      { id: "a", kind: "added", spans: [ { page: 1, start: 4, end: 7 } ] },
      { id: "r", kind: "removed", spans: [ { page: 2, start: 0, end: 0 } ] },
      { id: "x", kind: "added", spans: [ { page: 3, start: 6, end: 6, starts: true }, { page: 3, start: 6, end: 9 } ] },
    ]);
  });
});

describe("getChangeInContext", () => {
  const inContext = (change: RevisionChange) => {
    const { before, removed, added, after } = getChangeInContext(change);
    return [ before, removed, added, after ];
  };

  it("shows what a change removed and added among the words around it, as the newer version reads", async () => {
    const { changes } = await compare(
      pages("Participants are recruited from the outpatient clinic by the study coordinator."),
      pages("Participants are recruited from the sleep clinic by the study coordinator."),
    );

    expect(inContext(changes[0])).toEqual([ "…recruited from the", "outpatient", "sleep", "clinic by the…" ]);
  });

  it("shows text removed among the words around it as the older version reads", async () => {
    const { changes } = await compare(
      pages("Posters go in the waiting room only. Data is kept."),
      pages("Posters go in the waiting room. Data is kept."),
    );

    expect(inContext(changes[0])).toEqual([ "…the waiting room", "only.", "", "" ]);
  });

  it("keeps the words around a change within its sentence", async () => {
    const { changes } = await compare(
      pages("The study ends. Then data is kept for good."),
      pages("The study ends. Then all data is kept for good."),
    );

    expect(inContext(changes[0])).toEqual([ "Then", "", "all", "data is kept…" ]);
  });

  it("keeps the words around a change short of the changes shown with it", async () => {
    const { changes } = await compareAll(pages("The Fee 100 is due."), pages("The fee 120 is due."));
    const inContext = (change: RevisionChange) => {
      const { before, removed, added, after } = getChangeInContext(change);
      return [ before, removed, added, after ];
    };

    // A minor change is shown with all the others, the others without the minor ones
    expect(changes.map(inContext)).toEqual([ [ "The", "Fee", "fee", "" ], [ "The fee", "100", "120", "is due." ] ]);
  });

  it("keeps the words around a change short of the changes either side", async () => {
    const { changes } = await compare(pages("The cat sat on mat today."), pages("The dog sat on rug today."));

    expect(changes.map(inContext)).toEqual([
      [ "The", "cat", "dog", "sat on…" ],
      [ "…sat on", "mat", "rug", "today." ],
    ]);
  });

  it("shows no words beyond the ends of the text", async () => {
    const { changes } = await compare(pages("The fee is due."), pages("Now the fee is due."));

    expect(inContext(changes[0])).toEqual([ "", "", "Now", "the fee is…" ]);
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
