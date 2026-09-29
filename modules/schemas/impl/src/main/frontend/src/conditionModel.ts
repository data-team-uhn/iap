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

// The conditions of a schema version, read with the conditions module's model and one source of the schemas' own:
// an `answer` operand holds the answers to the version's question it names. No React, no fetch.

import {
  type Choice, type OperandShape, type OperandSource, OWN_PROPERTY_SOURCE, PROPERTY_SOURCE, tagsSource,
  type ValueType,
} from "@iap/conditions/conditionModel";

import { headingOf, optionLabelOf, optionsOf, type QuestionIndex, strings } from "./schemaVersionTreeModel";

import type { JcrNode } from "./schemaModel";

// The comparison types of the question data types; a file compares as nothing in particular
const VALUE_TYPES: Record<string, ValueType | undefined> = {
  text: "text",
  long: "long",
  double: "double",
  boolean: "boolean",
  date: "date",
};

// What the answers to a question hold: its type, one or several of them, and its options when it lists some
export function answerShapeOf(question: JcrNode): OperandShape {
  const options = optionsOf(question).map(option => ({
    value: strings(option.value).at(0) ?? "",
    label: optionLabelOf(option),
  }));
  const maxAnswers = typeof question.maxAnswers === "number" ? question.maxAnswers : 1;
  return {
    type: VALUE_TYPES[strings(question.dataType).at(0) ?? "text"],
    multiple: maxAnswers !== 1,
    ...options.length > 0 ? { choices: options } : {},
  };
}

export const answerSource = (index: QuestionIndex): OperandSource => ({
  name: "answer",
  label: "The answer to a question",
  valueLabel: "Question",
  shape: value => {
    const question = index.find(value.at(0) ?? "");
    return question ? answerShapeOf(question) : {};
  },
  describe: value => {
    const question = index.find(value.at(0) ?? "");
    return question
      ? `the answer to “${headingOf(question)}”`
      : `the answer to ${value.at(0) ?? "a missing question"}`;
  },
});

// Every source a schema's conditions may use, with the labels of the tags when they are known
export const schemaSources = (index: QuestionIndex, tags: Choice[] = []): OperandSource[] =>
  [ answerSource(index), tagsSource(tags), PROPERTY_SOURCE, OWN_PROPERTY_SOURCE ];
