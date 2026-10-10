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

import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import {
  compareVersions, type ComparisonSettings, keptInOrder, type PartComparison,
} from "@iap/schemas/schemaComparisonModel";

const node = (name: string, properties: SerializedNode, children: SerializedNode[] = []): SerializedNode => ({
  "@name": name, ...properties, ...Object.fromEntries(children.map(child => [ String(child["@name"]), child ])),
});
const version = (...parts: SerializedNode[]) => node("v1", { "sling:resourceType": "sch/SchemaVersion" }, parts);
const form = (name: string, label: string, ...items: SerializedNode[]) => node(name,
  { "sling:resourceType": "sch/FormRequirement", "sling:resourceSuperType": "sch/Requirement", label }, items);
const section = (name: string, title: string, ...items: SerializedNode[]) => node(name,
  { "sling:resourceType": "sch/Section", "sling:resourceSuperType": "sch/FormItem", title }, items);
const question = (name: string, text: string, properties: SerializedNode = {}, ...options: SerializedNode[]) =>
  node(name, { "sling:resourceType": "sch/Question", "sling:resourceSuperType": "sch/FormItem", text, ...properties },
    options);
const option = (value: string, label?: string, name = value) => node(name,
  { "sling:resourceType": "sch/AnswerOption", value, label });

// Conditions stand in as what they compare by and the sentences describing them, kept on the part for these tests
const said = (part: SerializedNode) => (typeof part.when === "string" || typeof part.whenKey === "string"
  ? { key: String(part.whenKey ?? part.when), words: part.when as string | undefined } : undefined);
const SETTINGS: ComparisonSettings = {
  versionFields: [ { name: "description", label: "Description" } ],
  partFields: [
    { name: "label", label: "Label" }, { name: "title", label: "Title" }, { name: "text", label: "Question" },
    { name: "dataType", label: "Answer type" }, { name: "minAnswers", label: "Minimum answers" },
  ],
  optionFields: [ { name: "value", label: "Value" }, { name: "label", label: "Label" } ],
  conditionOf: { before: said, after: said },
};

// The outline compactly: each part as its change, its name and what is said about it, indented by depth
const outline = (parts: PartComparison[], depth = 0): string[] => parts.flatMap(part => [
  `${"  ".repeat(depth)}${part.change} ${part.name}${part.movedFrom !== undefined
    ? ` (moved from ${part.movedFrom ?? "the top level"})` : ""}${part.reordered ? " (reordered)" : ""}`,
  ...outline(part.parts, depth + 1),
]);

describe("compareVersions", () => {
  const intake = () => form("intake", "Intake",
    question("name", "Your name"), question("age", "Your age", { dataType: "long" }));

  it("finds nothing changed between two copies of a version", () => {
    const comparison = compareVersions(version(intake()), version(intake()), SETTINGS);

    expect(outline(comparison.parts)).toEqual([ "unchanged intake", "  unchanged name", "  unchanged age" ]);
    expect(comparison.summary).toEqual({ added: 0, removed: 0, changed: 0, moved: 0 });
  });

  it("says which fields of a part changed, by the names they are edited under", () => {
    const after = version(form("intake", "Intake",
      question("name", "Your full name"), question("age", "Your age", { dataType: "text", minAnswers: 1 })));

    const comparison = compareVersions(version(intake()), after, SETTINGS);

    const [ name, age ] = comparison.parts[0].parts;
    expect(name.change).toBe("changed");
    expect(name.fields).toEqual([
      { field: "text", label: "Question", change: "changed", before: "Your name", after: "Your full name" },
    ]);
    expect(age.fields).toEqual([
      { field: "dataType", label: "Answer type", change: "changed", before: "long", after: "text" },
      { field: "minAnswers", label: "Minimum answers", change: "added", after: 1 },
    ]);
    expect(comparison.summary).toEqual({ added: 0, removed: 0, changed: 2, moved: 0 });
  });

  it("shows a removed part where it was, and an added one where it is", () => {
    const before = version(form("intake", "Intake",
      question("name", "Your name"), question("email", "Your email"), question("age", "Your age")));
    const after = version(form("intake", "Intake",
      question("name", "Your name"), question("age", "Your age"), question("phone", "Your phone")));

    const comparison = compareVersions(before, after, SETTINGS);

    expect(outline(comparison.parts)).toEqual([
      "unchanged intake", "  unchanged name", "  removed email", "  unchanged age", "  added phone",
    ]);
    expect(comparison.summary).toEqual({ added: 1, removed: 1, changed: 0, moved: 0 });
  });

  it("shows a removed first part first", () => {
    const before = version(form("intake", "Intake", question("email", "Your email"), question("name", "Your name")));
    const after = version(form("intake", "Intake", question("name", "Your name")));

    expect(outline(compareVersions(before, after, SETTINGS).parts))
      .toEqual([ "unchanged intake", "  removed email", "  unchanged name" ]);
  });

  it("says where a part moved from, without counting it as removed and added", () => {
    const before = version(form("intake", "Intake", section("about", "About you", question("age", "Your age")),
      section("contact", "Contact")));
    const after = version(form("intake", "Intake", section("about", "About you"),
      section("contact", "Contact", question("age", "Your age"))));

    const comparison = compareVersions(before, after, SETTINGS);

    expect(outline(comparison.parts)).toEqual([
      "unchanged intake", "  unchanged about", "  unchanged contact", "    unchanged age (moved from About you)",
    ]);
    expect(comparison.summary).toEqual({ added: 0, removed: 0, changed: 0, moved: 1 });
  });

  it("says which parts were reordered among the same siblings", () => {
    const before = version(form("intake", "Intake",
      question("name", "Your name"), question("age", "Your age"), question("email", "Your email")));
    const after = version(form("intake", "Intake",
      question("email", "Your email"), question("name", "Your name"), question("age", "Your age")));

    const comparison = compareVersions(before, after, SETTINGS);

    expect(outline(comparison.parts)).toEqual([
      "unchanged intake", "  unchanged email (reordered)", "  unchanged name", "  unchanged age",
    ]);
    expect(comparison.summary.moved).toBe(1);
  });

  it("matches a part that moved to the top of the version, from where it was", () => {
    const before = version(form("intake", "Intake", question("age", "Your age")));
    const after = version(form("intake", "Intake"), question("age", "Your age"));

    expect(outline(compareVersions(before, after, SETTINGS).parts))
      .toEqual([ "unchanged intake", "unchanged age (moved from Intake)" ]);
    expect(outline(compareVersions(after, before, SETTINGS).parts))
      .toEqual([ "unchanged intake", "  unchanged age (moved from the top level)" ]);
  });

  it("does not match parts of different types, or a name more than one part goes by", () => {
    const before = version(form("intake", "Intake", question("about", "About"), question("age", "Age"),
      section("one", "One", question("notes", "Notes")), section("two", "Two", question("notes", "Notes"))));
    const after = version(form("intake", "Intake", section("about", "About"),
      section("one", "One"), section("two", "Two"), section("three", "Three", question("notes", "Notes"),
        question("age", "Age"))));

    expect(outline(compareVersions(before, after, SETTINGS).parts)).toEqual([
      "unchanged intake", "  removed about", "  added about", "  unchanged one", "    removed notes",
      "  unchanged two", "    removed notes", "  added three", "    added notes",
      "    unchanged age (moved from Intake)",
    ]);
  });

  it("matches what a moved part holds among what it held, though other parts share its identifiers", () => {
    const before = version(form("intake", "Intake",
      section("one", "One", question("notes", "Notes")), section("two", "Two", question("notes", "Notes"))));
    const after = version(form("intake", "Intake", section("more", "More",
      section("one", "One", question("notes", "Notes")), section("two", "Two", question("notes", "Notes")))));

    const comparison = compareVersions(before, after, SETTINGS);

    expect(outline(comparison.parts)).toEqual([
      "unchanged intake", "  added more", "    unchanged one (moved from Intake)", "      unchanged notes",
      "    unchanged two (moved from Intake)", "      unchanged notes",
    ]);
    expect(comparison.summary).toEqual({ added: 1, removed: 0, changed: 0, moved: 2 });
  });

  it("shows everything in an added or removed part as added or removed with it, except what moved", () => {
    const before = version(form("intake", "Intake", question("name", "Your name")),
      form("legacy", "Legacy", question("fax", "Your fax"), question("phone", "Your phone")));
    const after = version(form("intake", "Intake", question("name", "Your name"), question("phone", "Your phone")),
      form("followUp", "Follow-up", question("visit", "Next visit")));

    const comparison = compareVersions(before, after, SETTINGS);

    expect(outline(comparison.parts)).toEqual([
      "unchanged intake", "  unchanged name", "  unchanged phone (moved from Legacy)",
      "removed legacy", "  removed fax",
      "added followUp", "  added visit",
    ]);
    expect(comparison.summary).toEqual({ added: 2, removed: 2, changed: 0, moved: 1 });
  });

  it("compares when a part applies by the sentences saying so", () => {
    const before = version(form("intake", "Intake", question("age", "Your age", { when: "Only when adult" }),
      question("name", "Your name")));
    const after = version(form("intake", "Intake", question("age", "Your age", { when: "Only when a minor" }),
      question("name", "Your name", { when: "Only when asked" })));

    const [ age, name ] = compareVersions(before, after, SETTINGS).parts[0].parts;

    expect(age.change).toBe("changed");
    expect(age.condition).toEqual({ before: "Only when adult", after: "Only when a minor" });
    expect(name.condition).toEqual({ before: undefined, after: "Only when asked" });
  });

  it("takes a condition as unchanged when only the words of what it refers to changed", () => {
    const before = version(form("intake", "Intake",
      question("age", "Your age", { when: "Only when “Adult?” is Yes", whenKey: "adult is yes" })));
    const after = version(form("intake", "Intake",
      question("age", "Your age", { when: "Only when “Of age?” is Yes", whenKey: "adult is yes" })));

    const [ age ] = compareVersions(before, after, SETTINGS).parts[0].parts;

    expect(age.change).toBe("unchanged");
    expect(age.condition).toBeUndefined();
  });

  it("says what the version says of itself that changed", () => {
    const before = { ...version(intake()), description: "The first" };
    const after = { ...version(intake()), description: "The second" };

    expect(compareVersions(before, after, SETTINGS).version).toEqual([
      { field: "description", label: "Description", change: "changed", before: "The first", after: "The second" },
    ]);
  });

  it("matches a question's options by their names, and says what became of each", () => {
    const before = version(form("intake", "Intake", question("arm", "Which arm?", {},
      option("drug", "Drug"), option("placebo", "Placebo"), option("none", "None"))));
    const after = version(form("intake", "Intake", question("arm", "Which arm?", {},
      option("placebo", "Placebo arm"), option("drug", "Drug"), option("device", "Device"))));

    const [ arm ] = compareVersions(before, after, SETTINGS).parts[0].parts;

    expect(arm.change).toBe("changed");
    expect(arm.options.map(({ name, change, reordered }) => `${change} ${name}${reordered ? " (reordered)" : ""}`))
      .toEqual([ "changed placebo", "unchanged drug (reordered)", "removed none", "added device" ]);
    expect(arm.options[0].fields).toEqual([
      { field: "label", label: "Label", change: "changed", before: "Placebo", after: "Placebo arm" },
    ]);
    expect(arm.options[0].label).toBe("Placebo arm");
  });

  it("shows an option's edited value as a change of it", () => {
    const before = version(form("intake", "Intake", question("arm", "Which arm?", {}, option("drug", "Drug"))));
    const after = version(form("intake", "Intake",
      question("arm", "Which arm?", {}, option("medication", "Drug", "drug"))));

    const [ arm ] = compareVersions(before, after, SETTINGS).parts[0].parts;

    expect(arm.options[0].fields).toEqual([
      { field: "value", label: "Value", change: "changed", before: "drug", after: "medication" },
    ]);
  });

  it("counts a question whose options were only reordered as changed", () => {
    const before = version(form("intake", "Intake", question("arm", "Which arm?", {},
      option("drug", "Drug"), option("placebo", "Placebo"))));
    const after = version(form("intake", "Intake", question("arm", "Which arm?", {},
      option("placebo", "Placebo"), option("drug", "Drug"))));

    const comparison = compareVersions(before, after, SETTINGS);

    expect(comparison.parts[0].parts[0].change).toBe("changed");
    expect(comparison.summary.changed).toBe(1);
  });

  it("says what an added or removed part asks, and when it applies, but for its heading", () => {
    const before = version(form("intake", "Intake", question("old", "Old?", { dataType: "long", when: "Only if" })));
    const after = version(form("intake", "Intake", question("new", "New?", { dataType: "text", when: "Unless" })));

    const [ removed, added ] = compareVersions(before, after, SETTINGS).parts[0].parts;

    expect(removed.fields).toEqual([ { field: "dataType", label: "Answer type", change: "removed", before: "long" } ]);
    expect(removed.condition).toEqual({ before: "Only if" });
    expect(added.fields).toEqual([ { field: "dataType", label: "Answer type", change: "added", after: "text" } ]);
    expect(added.condition).toEqual({ after: "Unless" });
    expect(compareVersions(version(intake()), version(intake(), question("plain", "Plain")), SETTINGS).parts[1])
      .not.toHaveProperty("condition");
    // Nor a condition that cannot be put into words
    expect(compareVersions(version(intake()), version(intake(), question("odd", "Odd?", { whenKey: "odd" })),
      SETTINGS).parts[1]).not.toHaveProperty("condition");
  });

  it("lists an added or removed question's options as added or removed with it", () => {
    const before = version(form("intake", "Intake", question("old", "Old?", {}, option("yes"))));
    const after = version(form("intake", "Intake", question("new", "New?", {}, option("no"))));

    const [ removed, added ] = compareVersions(before, after, SETTINGS).parts[0].parts;

    expect(removed.options.map(({ name, change }) => `${change} ${name}`)).toEqual([ "removed yes" ]);
    expect(added.options.map(({ name, change }) => `${change} ${name}`)).toEqual([ "added no" ]);
    expect(added.heading).toBe("New?");
  });
});

describe("keptInOrder", () => {
  // Where several are as long, the one the newer order starts with
  it("keeps the longest sequence both orders share", () => {
    expect(keptInOrder([ "a", "b", "c" ], [ "b", "c", "a" ])).toEqual(new Set([ "b", "c" ]));
    expect(keptInOrder([ "a", "b" ], [ "b", "a" ])).toEqual(new Set([ "b" ]));
    expect(keptInOrder([], [])).toEqual(new Set());
  });
});
