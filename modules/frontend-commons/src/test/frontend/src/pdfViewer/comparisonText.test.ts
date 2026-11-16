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
import { describeChange, describeComparison, describeMinor } from "@iap/frontend-commons/pdfViewer/comparisonText";
import type { Comparison, RevisionChange } from "@iap/frontend-commons/pdfViewer/revisionDiffModel";

// A change on page 2 of the older version and page 4 of the newer, with no words around it.
function change(removed: string, added: string, minor = false): RevisionChange {
  const context = { before: "", after: "" };
  return {
    id: "c",
    minor,
    removed,
    added,
    older: [ { page: 2, start: 0, end: 1 } ],
    newer: [ { page: 4, start: 0, end: 1 } ],
    context: { older: context, newer: context },
  };
}

function compared(...changes: RevisionChange[]): Comparison {
  return { status: "compared", changes, alignment: { older: [], newer: [], pairs: [] } };
}

describe("describeChange", () => {
  const among = { index: 3, count: 17 };

  it("says which change it is, what it did on which page of the newer version, and its text", () => {
    expect(describeChange(change("", "now"), among)).toBe("Change 3 of 17. Text added on page 4. It says “now”.");
    expect(describeChange(change("old", ""), among)).toBe("Change 3 of 17. Text removed on page 4. It said “old”.");
    expect(describeChange(change("100", "120"), among))
      .toBe("Change 3 of 17. Text replaced on page 4. “100” became “120”.");
  });

  it("says what a change did alone, given no place among others", () => {
    expect(describeChange(change("100", "120"))).toBe("Text replaced on page 4. “100” became “120”.");
  });

  it("cuts long text short", () => {
    expect(describeChange(change("", `${"word ".repeat(30)}end`), among))
      .toBe(`Change 3 of 17. Text added on page 4. It says “${"word ".repeat(24).trimEnd()}…”.`);
  });
});

describe("describeMinor", () => {
  it("says how many minor changes are left out, or included", () => {
    expect(describeMinor(3, false)).toBe("3 minor changes of punctuation or letter case left out.");
    expect(describeMinor(1, false)).toBe("1 minor change of punctuation or letter case left out.");
    expect(describeMinor(3, true)).toBe("Including 3 minor changes of punctuation or letter case.");
    expect(describeMinor(0, false)).toBe("");
    expect(describeMinor(0, true)).toBe("");
  });
});

describe("describeComparison", () => {
  it("says how far the reading has got, with no count yet", () => {
    expect(describeComparison({ status: "reading", read: 3, total: 9 }, false))
      .toEqual({ caption: "Comparing… page 3 of 9", said: "Comparing the versions…" });
    expect(describeComparison({ status: "reading", read: 0, total: 0 }, false).caption).toBe("Comparing…");
    expect(describeComparison({ status: "comparing" }, false).caption).toBe("Comparing…");
  });

  it("says why there are no changes to show", () => {
    expect(describeComparison({ status: "noText", without: "older" }, false).caption)
      .toBe("The older version has no text to compare. It may be a scan.");
    expect(describeComparison({ status: "noText", without: "newer" }, false).said)
      .toBe("The newer version has no text to compare. It may be a scan.");
    expect(describeComparison({ status: "noText", without: "both" }, false).caption)
      .toBe("Neither version has text to compare. They may be scans.");
    expect(describeComparison({ status: "tooDifferent" }, false).caption)
      .toBe("These versions differ too much to show their changes.");
    expect(describeComparison({ status: "failed" }, false).caption).toBe("These versions could not be compared.");
  });

  it("counts the changes shown, and says what minor changes it leaves out or includes", () => {
    const state = compared(change("Fee", "fee", true), change("", "now"));
    const hidden = "1 minor change of punctuation or letter case left out.";
    const including = "Including 1 minor change of punctuation or letter case.";

    expect(describeComparison(state, false)).toEqual({ count: 1, caption: hidden, said: `1 change found. ${hidden}` });
    expect(describeComparison(state, true))
      .toEqual({ count: 2, caption: including, said: `2 changes found. ${including}` });
  });

  it("has no count when it finds no changes, and says so", () => {
    expect(describeComparison(compared(), false)).toEqual({ caption: "No changes found.", said: "No changes found." });
    expect(describeComparison(compared(change("Fee", "fee", true)), false).caption)
      .toBe("No changes found. 1 minor change of punctuation or letter case left out.");
  });
});
