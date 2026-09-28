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

// Conditions in words, for reading a schema: "“Is a copy available?” is “Yes”". The comparators and
// operand sources are the conditions module's (see docs/conditions.md); one it does not know is shown by
// its stored name rather than hidden. No React, no fetch.

import { headingOf, optionLabelOf, optionsOf, type QuestionIndex, strings } from "./schemaVersionTreeModel";

import type { JcrNode } from "./schemaModel";

const COMPARATORS: Record<string, string> = {
  "equals": "is",
  "not equals": "is not",
  "less than": "is less than",
  "less or equal": "is at most",
  "greater than": "is more than",
  "greater or equal": "is at least",
  "is empty": "is empty",
  "is not empty": "is not empty",
  "includes": "includes all of",
  "includes any": "includes any of",
  "excludes": "includes none of",
  "excludes any": "does not include all of",
};

const UNARY = new Set([ "is empty", "is not empty" ]);

const quoted = (value: string): string => (/^-?\d+(\.\d+)?$/.test(value) ? value : `“${value}”`);

const operand = (node: JcrNode, key: string): JcrNode => {
  const value = node[key];
  return typeof value === "object" && value !== null ? value as JcrNode : {};
};

// The question an answer operand names, if the version has it
const questionOf = (side: JcrNode, index: QuestionIndex): JcrNode | undefined =>
  side.source === "answer" ? index.find(strings(side.value).at(0) ?? "") : undefined;

function describeOperand(side: JcrNode, index: QuestionIndex): string {
  const values = strings(side.value);
  switch (side.source) {
    case "answer": {
      const question = questionOf(side, index);
      return question
        ? `the answer to “${headingOf(question)}”`
        : `the answer to ${values.at(0) ?? "a missing question"}`;
    }
    case "tags":
      return "its tag list";
    case "property":
      return `its ${values.at(0) ?? "property"}`;
    default:
      return values.map(quoted).join(", ") || "nothing";
  }
}

// A literal compared with a question's answer is one of its option values: shown by the option's label
function describeLiteral(side: JcrNode, question: JcrNode | undefined): string {
  if (!question || (side.source ?? "literal") !== "literal") {
    return "";
  }
  const labels = new Map(optionsOf(question).map(option => [ String(option.value), optionLabelOf(option) ]));
  return strings(side.value).map(value => quoted(labels.get(value) ?? value)).join(", ") || "nothing";
}

function describeSingle(condition: JcrNode, index: QuestionIndex): string {
  const comparator = String(condition.comparator);
  const left = operand(condition, "operandA");
  const right = operand(condition, "operandB");
  const phrase = `${describeOperand(left, index)} ${COMPARATORS[comparator] ?? comparator}`;
  if (UNARY.has(comparator)) {
    return phrase;
  }
  return `${phrase} ${describeLiteral(right, questionOf(left, index)) || describeOperand(right, index)}`;
}

const conditionsIn = (group: JcrNode): JcrNode[] => Object.values(group)
  .filter((child): child is JcrNode => typeof child === "object" && child !== null && !Array.isArray(child)
    && String((child as JcrNode)["sling:resourceSuperType"]) === "cond/Condition");

export function describeCondition(condition: JcrNode, index: QuestionIndex): string {
  if (condition["jcr:primaryType"] === "cond:SingleCondition") {
    return describeSingle(condition, index);
  }
  if (condition["jcr:primaryType"] === "cond:ConditionGroup") {
    const parts = conditionsIn(condition).map(child => {
      const described = describeCondition(child, index);
      return child["jcr:primaryType"] === "cond:ConditionGroup" ? `(${described})` : described;
    });
    const all = condition.requireAll === true;
    if (parts.length === 0) {
      return all ? "always" : "never";
    }
    return parts.join(all ? " and " : " or ");
  }
  return "a condition of an unknown kind holds";
}

// When a part applies, as the line shown on it: nothing for a condition that always holds
export function whenApplies(condition: JcrNode, index: QuestionIndex): string | undefined {
  const described = describeCondition(condition, index);
  if (described === "always") {
    return undefined;
  }
  return described === "never" ? "Never" : `Only when ${described}`;
}
