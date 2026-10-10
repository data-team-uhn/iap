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

import { fieldsOfDefinitions, isTextChange, shownValue } from "@iap/schemas/comparisonFields";

import { FIELD_DEFINITIONS } from "./schemaServer.fixture";

const fields = fieldsOfDefinitions([
  FIELD_DEFINITIONS.updateDraftSchemaPart, FIELD_DEFINITIONS.updateDraftAnswerOption,
]);
const field = (name: string) => fields.find(candidate => candidate.name === name);

describe("fieldsOfDefinitions", () => {
  it("finds every field the definitions describe, by the names they are edited under", () => {
    expect(fields.map(({ name, label }) => `${name}: ${label}`)).toEqual([
      "text: Question", "label: Label", "title: Title", "description: Description", "dataType: Answer type",
      "minAnswers: Minimum answers",
      "required: Required", "value: Value",
    ]);
    expect(field("dataType")?.choices).toEqual({ text: "Text", long: "Whole number" });
    expect(field("text")?.choices).toEqual({});
  });

  it("keeps the first description of a name, and a name for one without a label", () => {
    const found = fieldsOfDefinitions([
      { v1: { tags: [ "active" ], update: { fields: { label: { "@name": "label", label: "Label" } } } } },
      { v1: { tags: [ "active" ], update: { fields: {
        label: { "@name": "label", label: "Other" }, rubric: { "@name": "rubric" },
      } } } },
    ]);

    expect(found.map(({ name, label }) => `${name}: ${label}`)).toEqual([ "label: Label", "rubric: rubric" ]);
  });

  it("reads a choice's value and words as the server does, whatever the choice is called", () => {
    const found = fieldsOfDefinitions([ { v1: { tags: [ "active" ], update: { fields: { dataType: {
      "@name": "dataType", label: "Answer type",
      choices: { "@name": "choices", decimal: { "@name": "decimal", value: "double", label: "Decimal number" },
        long: { "@name": "long", value: "long" }, text: { "@name": "text", label: "Text" } },
    } } } } } ]);

    expect(found[0].choices).toEqual({ double: "Decimal number", long: "long", text: "Text" });
  });

  it("reads only the active version of a workflow", () => {
    const found = fieldsOfDefinitions([ {
      v1: { tags: [ "retired" ], update: { fields: { text: { "@name": "text", label: "Question" } } } },
      v2: { tags: [ "active" ], update: { fields: { text: { "@name": "text", label: "Prompt" } } } },
    } ]);

    expect(found.map(({ label }) => label)).toEqual([ "Prompt" ]);
  });
});

describe("shownValue", () => {
  it("says a value as people read it", () => {
    expect(shownValue(field("dataType"), "long")).toBe("Whole number");
    expect(shownValue(field("dataType"), "exotic")).toBe("exotic");
    expect(shownValue(field("required"), false)).toBe("No");
    expect(shownValue(field("required"), true)).toBe("Yes");
    expect(shownValue(field("minAnswers"), 2)).toBe("2");
    expect(shownValue(undefined, [ "a", "b" ])).toBe("a, b");
  });

  it("names a reference by what it points at, and nothing else by a name", () => {
    const workflow = { name: "workflow", label: "Workflow", choices: {}, referenceType: "wf/WorkflowVersion" };
    const names = { "/Workflows/fast": "Fast track" };

    expect(shownValue(workflow, "/Workflows/fast", names)).toBe("Fast track");
    expect(shownValue(field("text"), "/Workflows/fast", names)).toBe("/Workflows/fast");
    // Nor by what every object inherits
    expect(shownValue(workflow, "constructor", names)).toBe("constructor");
    expect(shownValue(field("dataType"), "toString")).toBe("toString");
  });
});

describe("isTextChange", () => {
  it("compares texts word by word, and listed choices or other values as replaced", () => {
    expect(isTextChange(field("text"), "Your age", "Your age in years")).toBe(true);
    expect(isTextChange(field("text"), undefined, "Your age")).toBe(true);
    expect(isTextChange(field("dataType"), "text", "long")).toBe(false);
    expect(isTextChange(field("minAnswers"), 1, 2)).toBe(false);
    expect(isTextChange(undefined, "a", "b")).toBe(true);
  });
});
