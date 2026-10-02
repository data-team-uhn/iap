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

import { compareFields, compareText, type TextLine } from "@iap/frontend-commons/diff/contentDiffModel";

// A comparison written compactly: each line as its mark (" ", "-", "+") and its text, the changed words in brackets
const shown = (lines: TextLine[]) => lines.map(line => {
  const mark = line.change === "added" ? "+" : line.change === "removed" ? "-" : " ";
  return mark + line.parts.map(part => (part.changed ? `[${part.text}]` : part.text)).join("");
});

describe("compareText", () => {
  it("says nothing changed in the same text", () => {
    expect(shown(compareText("Your name", "Your name"))).toEqual([ " Your name" ]);
  });

  it("marks the words that changed within a changed line", () => {
    expect(shown(compareText("What is your name?", "What is your full name?")))
      .toEqual([ "-What is your name?", "+What is your [full ]name?" ]);
    expect(shown(compareText("Your age in years", "Your age in months")))
      .toEqual([ "-Your age in [years]", "+Your age in [months]" ]);
  });

  it("keeps lines that did not change, and pairs the changed ones in order", () => {
    const before = "Decide whether this is a protocol.\nA protocol describes a study.\nDo not guess.";
    const after = "Decide whether this is a protocol.\nA protocol describes a planned study.\nDo not guess.";

    expect(shown(compareText(before, after))).toEqual([
      " Decide whether this is a protocol.",
      "-A protocol describes a study.",
      "+A protocol describes a [planned ]study.",
      " Do not guess.",
    ]);
  });

  it("shows lines with nothing in common as wholly removed and added", () => {
    expect(shown(compareText("Yes", "No"))).toEqual([ "-Yes", "+No" ]);
  });

  it("shows lines only one side has as removed or added", () => {
    expect(shown(compareText("First\nSecond", "First"))).toEqual([ " First", "-Second" ]);
    expect(shown(compareText("First", "First\nSecond"))).toEqual([ " First", "+Second" ]);
    expect(shown(compareText("One\nTwo", "Uno\nDos\nTres")))
      .toEqual([ "-One", "-Two", "+Uno", "+Dos", "+Tres" ]);
  });

  it("compares with nothing as all added or all removed", () => {
    expect(shown(compareText("", "New text"))).toEqual([ "+New text" ]);
    expect(shown(compareText("Old text", ""))).toEqual([ "-Old text" ]);
    expect(compareText("", "")).toEqual([]);
  });
});

describe("compareFields", () => {
  it("lists only the fields that differ, and how", () => {
    const before = { text: "Your age", minAnswers: 1, dataType: "text", unit: "years" };
    const after = { text: "Your age", minAnswers: 0, dataType: "long", description: "As of today" };

    expect(compareFields(before, after, [ "text", "minAnswers", "dataType", "unit", "description" ])).toEqual([
      { field: "minAnswers", change: "changed", before: 1, after: 0 },
      { field: "dataType", change: "changed", before: "text", after: "long" },
      { field: "unit", change: "removed", before: "years" },
      { field: "description", change: "added", after: "As of today" },
    ]);
  });

  it("compares only the fields asked about", () => {
    expect(compareFields({ text: "A", "jcr:uuid": "1" }, { text: "A", "jcr:uuid": "2" }, [ "text" ])).toEqual([]);
  });

  it("takes an empty value for none", () => {
    expect(compareFields({ description: "" }, {}, [ "description" ])).toEqual([]);
    expect(compareFields({ tags: [] }, { tags: null }, [ "tags" ])).toEqual([]);
    expect(compareFields({}, { description: "Now said" }, [ "description" ]))
      .toEqual([ { field: "description", change: "added", after: "Now said" } ]);
  });

  it("compares lists in order", () => {
    expect(compareFields({ tags: [ "a", "b" ] }, { tags: [ "a", "b" ] }, [ "tags" ])).toEqual([]);
    expect(compareFields({ tags: [ "a", "b" ] }, { tags: [ "b", "a" ] }, [ "tags" ]))
      .toEqual([ { field: "tags", change: "changed", before: [ "a", "b" ], after: [ "b", "a" ] } ]);
    expect(compareFields({ tags: [ "a" ] }, { tags: "a" }, [ "tags" ]))
      .toEqual([ { field: "tags", change: "changed", before: [ "a" ], after: "a" } ]);
  });

  it("takes no child node for a field's value, though it go by the field's name", () => {
    const child = { "jcr:primaryType": "sch:Question", "text": "The study's title" };

    expect(compareFields({ label: "Study", title: child }, { label: "Study" }, [ "label", "title" ])).toEqual([]);
    expect(compareFields({ title: child }, { title: "About" }, [ "title" ]))
      .toEqual([ { field: "title", change: "added", after: "About" } ]);
  });

  it("compares with what is not there at all", () => {
    expect(compareFields(undefined, { text: "New" }, [ "text" ]))
      .toEqual([ { field: "text", change: "added", after: "New" } ]);
    expect(compareFields({ text: "Old" }, undefined, [ "text" ]))
      .toEqual([ { field: "text", change: "removed", before: "Old" } ]);
  });
});
