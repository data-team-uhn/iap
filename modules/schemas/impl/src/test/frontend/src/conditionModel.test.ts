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

import { describeCondition as describeWith, whenApplies as appliesWith } from "@iap/conditions/conditionModel";
import { answerShapeOf, answerSource, schemaSources } from "@iap/schemas/conditionModel";
import { conditionOf, indexQuestions, partsOf } from "@iap/schemas/schemaVersionTreeModel";

import { CONTENT, HOMEPAGE, withPaths } from "./schemaServer.fixture";

const version = withPaths("/Schemas/study/v2", { ...HOMEPAGE.study.v2, ...CONTENT["study/v2"] });
const index = indexQuestions(version);
const describeCondition = (condition: Record<string, unknown>) => describeWith(condition, schemaSources(index));
const whenApplies = (condition: Record<string, unknown>) => appliesWith(condition, schemaSources(index));
const [ basics, consent, , reb, sign, audit ] = partsOf(version);
const [ , age, code ] = partsOf(partsOf(basics)[0]);
const described = (part: Record<string, unknown>) => describeCondition(conditionOf(part) ?? {});

describe("conditionModel", () => {
  it("names the question an answer is compared from, and the options it is compared with", () => {
    expect(described(age)).toBe("the answer to “Which arms does it have?” includes “Placebo”");
  });

  it("shows a value that is no option as it is, and compares answers with answers", () => {
    const withArms = (operandB: unknown) => describeCondition({
      "jcr:primaryType": "cond:SingleCondition", "comparator": "equals",
      "operandA": { source: "answer", value: [ "uuid-arms" ] }, operandB,
    });
    expect(withArms({ value: [ "drug", "other" ] }))
      .toBe("the answer to “Which arms does it have?” is “drug”, “other”");
    expect(withArms({ source: "literal", value: [] })).toBe("the answer to “Which arms does it have?” is nothing");
    expect(withArms({ source: "answer", value: [ "basics/design/age" ] }))
      .toBe("the answer to “Which arms does it have?” is the answer to “Minimum age”");
  });

  it("leaves out the second operand of a comparison that has none", () => {
    expect(described(code)).toBe("the answer to “Which arms does it have?” is not empty");
  });

  it("joins a group, bracketing the groups inside it, and shows what it does not know as it is stored", () => {
    expect(described(consent)).toBe("its tag list is “urgent”, 7 and "
      + "(its size is at least 10 or the answer to nowhere sounds like nothing)");
  });

  it("reads an empty group as what it evaluates to", () => {
    expect(described(reb)).toBe("never");
    expect(described(sign)).toBe("always");
  });

  it("words when a part applies, saying nothing of a condition that always holds", () => {
    const when = (part: Record<string, unknown>) => whenApplies(conditionOf(part) ?? {});
    expect(when(code)).toBe("Only when the answer to “Which arms does it have?” is not empty");
    expect(when(reb)).toBe("Never");
    expect(when(sign)).toBeUndefined();
  });

  it("says when a condition is of a kind it does not know", () => {
    expect(described(audit)).toBe("a condition of an unknown kind holds");
  });

  it("copes with operands missing what they name", () => {
    const bare = (operandA: unknown) => describeCondition({
      "jcr:primaryType": "cond:SingleCondition", "comparator": "equals", operandA,
      "operandB": { source: "literal", value: [ "x" ] },
    });
    expect(bare({ source: "answer" })).toBe("the answer to a missing question is “x”");
    expect(bare({ source: "property" })).toBe("its property is “x”");
    expect(bare(undefined)).toBe("nothing is “x”");
  });

  it("says what the answers to a question hold: its type, how many, and its options", () => {
    const arms = index.find("uuid-arms")!;
    expect(answerShapeOf(arms)).toMatchObject({ type: "text", multiple: true,
      choices: expect.arrayContaining([ { value: "placebo", label: "Placebo" } ]) as unknown });
    expect(answerShapeOf(age)).toEqual({ type: "long", multiple: true });
    expect(answerShapeOf(code)).toEqual({ type: undefined, multiple: false });
    expect(answerShapeOf({ "maxAnswers": 1, "dataType": "boolean", "yes": {
      "sling:resourceType": "sch/AnswerOption", "label": "Yes",
    } })).toEqual({ type: "boolean", multiple: false, choices: [ { value: "", label: "Yes" } ] });
    expect(answerShapeOf({})).toEqual({ type: "text", multiple: false });
    expect(answerSource(index).shape([ "nowhere" ])).toEqual({});
    expect(answerSource(index).shape([])).toEqual({});
  });
});
