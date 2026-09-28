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
  applicableFields, applies, blockingFields, candidateOf, changesOf, type ContentField, fieldsOf, initialValue,
  initialValues, isValid, patchValueOf, referenceQuery,
} from "@iap/frontend-commons/fields/fieldsModel";

const field = (name: string, extra: Partial<ContentField> = {}): ContentField =>
  ({ name, label: name, kind: "text", multiple: false, mandatory: false, multiline: false, ...extra });

const TITLE = field("title", { mandatory: true });
const TYPE = field("dataType", { choices: [ { value: "text", label: "Text" }, { value: "long", label: "Whole" } ] });
const BOUND = field("minValue", { kind: "double", appliesWhen: { property: "dataType", values: [ "long" ] } });
const COUNT = field("maxAnswers", { kind: "long" });
const REQUIRED = field("required", { kind: "boolean" });
const TYPES = field("acceptedFileTypes", { multiple: true });
const FLAGS = field("flags", { kind: "boolean", multiple: true });
const WORKFLOW = field("workflow", { kind: "reference", referenceType: "wf/Version", referenceRoot: "/Workflows/" });

const NODE = {
  "title": "Age",
  "dataType": "long",
  "minValue": 18,
  "maxAnswers": 1,
  "required": true,
  "acceptedFileTypes": [ "pdf", "doc" ],
  "workflow": { "@path": "/Workflows/review/v1", "version": "1.0" },
};

describe("fieldsOf", () => {
  it("reads the fields the serialization lists, leaving out anything unreadable", () => {
    expect(fieldsOf({ "@fields": [ TITLE, { name: "x", label: "X", kind: "date" }, { label: "No name" }, "title" ] }))
      .toEqual([ TITLE ]);
    expect(fieldsOf({})).toEqual([]);
  });
});

describe("initialValue", () => {
  it("starts from what is stored, as text", () => {
    expect(initialValue(NODE, TITLE)).toBe("Age");
    expect(initialValue(NODE, BOUND)).toBe("18");
    expect(initialValue(NODE, TYPES)).toEqual([ "pdf", "doc" ]);
    expect(initialValue(NODE, WORKFLOW)).toBe("/Workflows/review/v1");
    expect(initialValue({ workflow: "/Workflows/fast/v2" }, WORKFLOW)).toBe("/Workflows/fast/v2");
    expect(initialValue({ flags: [ true, false ] }, FLAGS)).toEqual([ "true", "false" ]);
  });

  it("starts empty when nothing readable is stored", () => {
    expect(initialValue({}, TITLE)).toBe("");
    expect(initialValue({}, TYPES)).toEqual([]);
    expect(initialValue({ title: { nothing: "here" } }, TITLE)).toBe("");
  });

  it("turns a switch on only for true", () => {
    expect(initialValue(NODE, REQUIRED)).toBe(true);
    expect(initialValue({}, REQUIRED)).toBe(false);
    expect(initialValue({ required: "true" }, REQUIRED)).toBe(false);
  });

  it("starts every field at once", () => {
    expect(initialValues(NODE, [ TITLE, REQUIRED ])).toEqual({ title: "Age", required: true });
  });
});

describe("applies", () => {
  const fields = [ TYPE, BOUND ];

  it("follows the value being entered for the property it depends on", () => {
    expect(applies(BOUND, fields, { dataType: "long" }, {})).toBe(true);
    expect(applies(BOUND, fields, { dataType: " text " }, NODE)).toBe(false);
    expect(applies(BOUND, fields, { dataType: [ "text", "long" ] }, {})).toBe(true);
    expect(applies(BOUND, fields, { dataType: true }, {})).toBe(false);
  });

  it("follows what is stored when that property is not being edited", () => {
    expect(applies(BOUND, [ BOUND ], {}, NODE)).toBe(true);
    expect(applies(BOUND, [ BOUND ], {}, {})).toBe(false);
  });

  it("always applies without a rule, and nowhere with one naming no property", () => {
    expect(applies(TITLE, fields, {}, {})).toBe(true);
    const nowhere = field("x", { appliesWhen: { property: "", values: [ "" ] } });
    expect(applies(nowhere, [ nowhere ], {}, { "": "" })).toBe(false);
  });

  it("lists the fields that apply", () => {
    expect(applicableFields(fields, { dataType: "text" }, {})).toEqual([ TYPE ]);
  });
});

describe("patchValueOf", () => {
  it("gives text trimmed, and blank as a removal", () => {
    expect(patchValueOf(TITLE, " Age ")).toBe("Age");
    expect(patchValueOf(TITLE, " ")).toBeNull();
  });

  it("gives numbers as numbers", () => {
    expect(patchValueOf(COUNT, " 3 ")).toBe(3);
    expect(patchValueOf(COUNT, "-2")).toBe(-2);
    expect(patchValueOf(BOUND, "1.5")).toBe(1.5);
    expect(patchValueOf(BOUND, "1e3")).toBe(1000);
  });

  it("refuses what is not a number of the field's kind", () => {
    expect(patchValueOf(COUNT, "1.5")).toBeUndefined();
    expect(patchValueOf(COUNT, "99999999999999999999")).toBeUndefined();
    expect(patchValueOf(BOUND, "many")).toBeUndefined();
    expect(patchValueOf(BOUND, "Infinity")).toBeUndefined();
    expect(isValid(COUNT, "x")).toBe(false);
    expect(isValid(COUNT, "")).toBe(true);
  });

  it("gives several values as a list, and none as a removal", () => {
    expect(patchValueOf(TYPES, [ " pdf ", "", "doc" ])).toEqual([ "pdf", "doc" ]);
    expect(patchValueOf(TYPES, [])).toBeNull();
    expect(patchValueOf(field("sizes", { kind: "long", multiple: true }), [ "1", "x" ])).toBeUndefined();
    expect(patchValueOf(FLAGS, [ "true", "false" ])).toEqual([ true, false ]);
    expect(patchValueOf(FLAGS, [ "maybe" ])).toBeUndefined();
  });

  it("gives a switch as it is", () => {
    expect(patchValueOf(REQUIRED, false)).toBe(false);
  });
});

describe("changesOf", () => {
  const fields = [ TITLE, TYPE, BOUND, COUNT, REQUIRED, TYPES ];

  it("sends only what changed", () => {
    const values = { ...initialValues(NODE, fields), title: " Age ", minValue: "21", required: false };
    expect(changesOf(fields, values, NODE)).toEqual({ minValue: 21, required: false });
    expect(changesOf(fields, initialValues(NODE, fields), NODE)).toEqual({});
  });

  it("sends a cleared field as a removal", () => {
    expect(changesOf(fields, { ...initialValues(NODE, fields), acceptedFileTypes: [] }, NODE))
      .toEqual({ acceptedFileTypes: null });
  });

  it("leaves out what stops applying, and what is not valid", () => {
    const values = { ...initialValues(NODE, fields), dataType: "text", minValue: "3", maxAnswers: "x" };
    expect(changesOf(fields, values, NODE)).toEqual({ dataType: "text" });
  });

  it("names what keeps a save back", () => {
    const values = { ...initialValues(NODE, fields), title: "", maxAnswers: "x", minValue: "" };
    expect(blockingFields(fields, values, NODE)).toEqual([ TITLE, COUNT ]);
    expect(blockingFields(fields, initialValues(NODE, fields), NODE)).toEqual([]);
  });
});

describe("references", () => {
  it("looks for the nodes of the field's type under its root", () => {
    expect(referenceQuery(WORKFLOW)).toBe("select * from [nt:base] as n where n.[sling:resourceType] = 'wf/Version'"
      + " and isdescendantnode(n, '/Workflows')");
    expect(referenceQuery(field("x", { kind: "reference", referenceType: "o'dd", referenceRoot: "/" })))
      .toBe("select * from [nt:base] as n where n.[sling:resourceType] = 'o''dd' and isdescendantnode(n, '/')");
    expect(referenceQuery(field("x", { kind: "reference", referenceType: "t" })))
      .toBe("select * from [nt:base] as n where n.[sling:resourceType] = 't'");
    expect(referenceQuery(field("x", { kind: "reference" }))).toBeUndefined();
  });

  it("offers a node by its name, or else by where it is", () => {
    expect(candidateOf({ "@path": "/Workflows/fast/v2", "title": "Fast track" }, "/Workflows"))
      .toEqual({ path: "/Workflows/fast/v2", label: "Fast track" });
    expect(candidateOf({ "@path": "/Workflows/fast/v2", "title": " ", "label": "Fast" }, "/Workflows"))
      .toEqual({ path: "/Workflows/fast/v2", label: "Fast" });
    expect(candidateOf({ "@path": "/Workflows/review/v1" }, "/Workflows/"))
      .toEqual({ path: "/Workflows/review/v1", label: "review/v1" });
    expect(candidateOf({ "@path": "/Elsewhere/v1" }, "/Workflows")).toEqual({ path: "/Elsewhere/v1", label: "/Elsewhere/v1" });
    expect(candidateOf({ "@path": "/Elsewhere/v1" })).toEqual({ path: "/Elsewhere/v1", label: "/Elsewhere/v1" });
    expect(candidateOf({ title: "No path" })).toBeUndefined();
  });
});
