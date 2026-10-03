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
  type Choice, type OperandShape, type OperandSource, ownPropertySource, propertySource, tagsSource,
  type ValueType,
} from "@iap/conditions/conditionModel";

import { isObject, type JcrNode, nameOf, pathOf } from "./schemaModel";
import { headingOf, optionLabelOf, optionsOf, type QuestionIndex, strings } from "./schemaVersionTreeModel";

// The comparison types of the question data types; a file compares as nothing in particular
const VALUE_TYPES: Record<string, ValueType | undefined> = {
  text: "text",
  long: "long",
  double: "double",
  boolean: "boolean",
  date: "date",
};

// What the answers to a question hold: its type, one or several of them, and its options when it lists some
// The items a question's options come from, by the path it takes them from
export type ItemChoices = Record<string, Choice[]>;

// The items under a node, as its deep serialization gives them: each by its path, called by its title or label
export function itemChoicesOf(node: JcrNode): Choice[] {
  return Object.entries(node)
    .filter((entry): entry is [ string, JcrNode ] => isObject(entry[1]) && !entry[0].includes(":"))
    .flatMap(([ name, item ]) => [
      { value: String(item["@path"]), label: strings(item.title ?? item.label).at(0) ?? name },
      ...itemChoicesOf(item),
    ]);
}

export function answerShapeOf(question: JcrNode, items: ItemChoices = {}): OperandShape {
  const listed = optionsOf(question).map(option => ({
    value: strings(option.value).at(0) ?? "",
    label: optionLabelOf(option),
  }));
  const options = listed.length > 0 ? listed : items[strings(question.optionsFrom).at(0) ?? ""] ?? [];
  const maxAnswers = typeof question.maxAnswers === "number" ? question.maxAnswers : 1;
  return {
    type: VALUE_TYPES[strings(question.dataType).at(0) ?? "text"],
    multiple: maxAnswers !== 1,
    ...options.length > 0 ? { choices: options } : {},
  };
}

export const answerSource = (index: QuestionIndex, items: ItemChoices = {}): OperandSource => ({
  name: "answer",
  label: "The answer to a question",
  valueLabel: "Question",
  shape: value => {
    const question = index.find(value.at(0) ?? "");
    return question ? answerShapeOf(question, items) : {};
  },
  describe: value => {
    const question = index.find(value.at(0) ?? "");
    return question
      ? `the answer to “${headingOf(question)}”`
      : `the answer to ${value.at(0) ?? "a missing question"}`;
  },
});

// Every source a schema's conditions may use, with the labels of the tags when they are known
export const schemaSources = (index: QuestionIndex, tags: Choice[] = [], items: ItemChoices = {}): OperandSource[] =>
  [ answerSource(index, items), tagsSource(tags, "submission"), propertySource("submission"),
    ownPropertySource("part") ];

// The sources the editor offers, of all a schema's conditions may use: what a submission answers, how it is tagged,
// and its properties
export const offeredOf = (sources: OperandSource[]): OperandSource[] =>
  sources.filter(source => source.name !== "ownProperty");

// The questions a part's condition can compare the answers to: any in its version, but itself and what it holds
export const questionsFor = (part: JcrNode, index: QuestionIndex): JcrNode[] => index.questions
  .filter(question => pathOf(question) !== pathOf(part) && !pathOf(question).startsWith(`${pathOf(part)}/`));

// A condition as two versions can compare it: what it stores, without the repository's bookkeeping, and the
// questions it compares the answers of by their identifiers in the version, which a copy keeps and a move does not
// change, rather than by the identifiers a copy changes. A question whose identifier another shares goes by where it
// stands. Keys are sorted, so that the order they were written in does not count.
export function conditionKeyOf(condition: JcrNode, index: QuestionIndex, version: string): string {
  const keyOf = (question: JcrNode) => (index.questions.filter(other => nameOf(other) === nameOf(question)).length === 1
    ? nameOf(question) : pathOf(question).slice(version.length + 1));
  const canonical = (node: JcrNode): JcrNode => Object.fromEntries(Object.entries(node)
    .filter(([ key ]) => !/^(jcr:|sling:|@)/.test(key))
    .sort(([ first ], [ second ]) => first.localeCompare(second))
    .map(([ key, value ]) => {
      if (isObject(value)) {
        return [ key, canonical(value) ];
      }
      if (key === "value" && node.source === "answer") {
        return [ key, strings(value).map(reference => {
          const question = index.find(reference);
          return question ? keyOf(question) : reference;
        }) ];
      }
      return [ key, value ];
    }));
  return JSON.stringify(canonical(condition));
}
