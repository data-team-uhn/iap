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
  answerCountOf, boundsOf, conditionOf, dataTypeOf, headingOf, indexQuestions, resourceTypeOf, optionLabelOf,
  optionsOf, partsOf, strings,
} from "@iap/schemas/schemaVersionTreeModel";

import { CONTENT, HOMEPAGE, withPaths } from "./schemaServer.fixture";

const version = withPaths("/Schemas/study/v2", { ...HOMEPAGE.study.v2, ...CONTENT["study/v2"] });
const [ basics, consent ] = partsOf(version);
const [ design ] = partsOf(basics);
const [ arms, age, code, site, lead, note ] = partsOf(design);

describe("schemaVersionTreeModel", () => {
  it("reads the parts of a version, in order, leaving everything else out", () => {
    expect(partsOf(version).map(headingOf)).toEqual([
      "Basic information", "Consent form", "Protocol", "Ethics approval", "Sign-off", "Audit",
    ]);
    expect(partsOf(design).map(resourceTypeOf)).toEqual(Array(6).fill("sch/Question"));
    expect(headingOf({ "@name": "unnamed", "label": " " })).toBe("unnamed");
  });

  it("reads a question's options by their places, even under names that look like numbers", () => {
    const question = withPaths("/q", {
      "2": { "sling:resourceType": "sch/AnswerOption", "value": "2", "defaultOrder": 20 },
      "10": { "sling:resourceType": "sch/AnswerOption", "value": "10", "defaultOrder": 10 },
      "yes": { "sling:resourceType": "sch/AnswerOption", "value": "yes", "defaultOrder": 30 },
      "cond:condition": { "jcr:primaryType": "cond:SingleCondition" },
    });
    expect(optionsOf(question).map(option => option.value)).toEqual([ "10", "2", "yes" ]);
    // Before the ones numbered, when read without a place
    expect(optionsOf({ ...question, yes: { "sling:resourceType": "sch/AnswerOption", "value": "yes" } })
      .map(option => option.value)).toEqual([ "yes", "10", "2" ]);
  });

  it("reads a question's options and condition", () => {
    expect(optionsOf(arms).map(optionLabelOf)).toEqual([ "Placebo", "drug" ]);
    expect(optionLabelOf({})).toBe("");
    expect(conditionOf(age)?.comparator).toBe("includes");
    expect(conditionOf(arms)).toBeUndefined();
    expect(conditionOf(consent)?.requireAll).toBe(true);
  });

  it("says what a question takes, in words", () => {
    expect(dataTypeOf(arms)).toBe("Text");
    expect(dataTypeOf(age)).toBe("Whole number");
    expect(dataTypeOf(code)).toBe("exotic");
    expect(dataTypeOf({})).toBe("Text");
    expect(answerCountOf(arms)).toEqual([ "Required", "Any number of answers" ]);
    expect(answerCountOf(age)).toEqual([ "Required", "Up to 3 answers", "At least 2 answers" ]);
    expect(answerCountOf(note)).toEqual([ "Optional" ]);
    expect(answerCountOf({})).toEqual([ "Optional" ]);
    expect([ age, site, lead, note ].map(boundsOf))
      .toEqual([ "Between 18 and 99", "At least 1", "At most 5", undefined ]);
  });

  it("finds questions by UUID or by their path in the version", () => {
    const index = indexQuestions(version);
    expect(index.find("uuid-arms")).toBe(arms);
    expect(index.find("basics/design/age")).toBe(age);
    expect(index.find("basics/design")).toBeUndefined();
  });

  it("reads a list of values, or a single one, as strings", () => {
    expect(strings([ "a", 1, null, { b: 2 } ])).toEqual([ "a", "1" ]);
    expect(strings("one")).toEqual([ "one" ]);
    expect(strings(undefined)).toEqual([]);
  });
});
