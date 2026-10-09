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

// Reading a schema version's content, its requirements and the form items inside them, straight from
// its serialization. What counts as a part is what the server says: every requirement and form item
// resolves to sch/SchemaPart through sch/Requirement or sch/FormItem. No React, no fetch.

import { fieldsOf } from "@iap/frontend-commons/fields/fieldsModel";
import { childrenOf, isNode, type SerializedNode } from "@iap/frontend-commons/serializedNode";

import { nameOf } from "./schemaModel";

const PART_SUPERTYPES = [ "sch/Requirement", "sch/FormItem" ];

export const OPTION_TYPE = "sch/AnswerOption";

export const QUESTION_TYPE = "sch/Question";

const text = (node: SerializedNode, key: string): string | undefined => {
  const value = node[key];
  return typeof value === "string" && value.trim() !== "" ? value : undefined;
};

const number = (node: SerializedNode, key: string): number | undefined => {
  const value = node[key];
  return typeof value === "number" ? value : undefined;
};

export const strings = (value: unknown): string[] => (Array.isArray(value) ? value : [ value ])
  .filter((item): item is string | number => typeof item === "string" || typeof item === "number")
  .map(String);

export const resourceTypeOf = (node: SerializedNode): string => String(node["sling:resourceType"]);

// Whether a node is a requirement or a form item, which has an identifier, as an option does not
export const isPart = (node: SerializedNode): boolean => PART_SUPERTYPES.includes(String(node["sling:resourceSuperType"]));

export const partsOf = (node: SerializedNode): SerializedNode[] => childrenOf(node).filter(isPart);

export const isQuestion = (node: SerializedNode): boolean => resourceTypeOf(node) === QUESTION_TYPE;

// A question's options, in the order it keeps them
export const optionsOf = (question: SerializedNode): SerializedNode[] => childrenOf(question)
  .filter(child => resourceTypeOf(child) === OPTION_TYPE);

export const conditionOf = (part: SerializedNode): SerializedNode | undefined => {
  const condition = part["cond:condition"];
  return isNode(condition) ? condition : undefined;
};

// What a part is called where it is shown: a requirement's label, a section's title, a question's text
export const headingOf = (part: SerializedNode): string =>
  text(part, "text") ?? text(part, "label") ?? text(part, "title") ?? nameOf(part);

export const detailOf = (node: SerializedNode, key: string): string | undefined => text(node, key);

export const optionLabelOf = (option: SerializedNode): string => text(option, "label") ?? strings(option.value).at(0) ?? "";

// What a part or an option is called where it is shown
export const shownNameOf = (node: SerializedNode): string =>
  resourceTypeOf(node) === OPTION_TYPE ? optionLabelOf(node) : headingOf(node);

// The words the workflows editing questions have for each type of answer, for a question no update describes
const DATA_TYPES: Partial<Record<string, string>> = {
  text: "Text",
  long: "Whole number",
  double: "Decimal number",
  boolean: "Yes or no",
  date: "Date",
  file: "File",
};

// A question's type of answer, in the words of the update that would change it, as its edit dialog shows it
export const dataTypeOf = (question: SerializedNode): string => {
  const dataType = text(question, "dataType") ?? "text";
  const choices = fieldsOf(question).find(field => field.name === "dataType")?.choices ?? [];
  return choices.find(choice => choice.value === dataType)?.label
    ?? (Object.hasOwn(DATA_TYPES, dataType) ? DATA_TYPES[dataType] : undefined) ?? dataType;
};

// How many answers a question takes, in words: a positive minimum makes it required, and a maximum other
// than one lets it take several, with zero or less meaning no limit
export function answerCountOf(question: SerializedNode): string[] {
  const min = number(question, "minAnswers") ?? 0;
  const max = number(question, "maxAnswers") ?? 1;
  const counts = [ min > 0 ? "Required" : "Optional" ];
  if (max <= 0) {
    counts.push("Any number of answers");
  } else if (max > 1) {
    counts.push(`Up to ${max} answers`);
  }
  if (min > 1) {
    counts.push(`At least ${min} answers`);
  }
  return counts;
}

// The range a numeric answer must fall in, in words, when it has one
export function boundsOf(question: SerializedNode): string | undefined {
  const min = number(question, "minValue");
  const max = number(question, "maxValue");
  if (min !== undefined && max !== undefined) {
    return `Between ${min} and ${max}`;
  }
  if (min !== undefined) {
    return `At least ${min}`;
  }
  return max === undefined ? undefined : `At most ${max}`;
}

// Where conditions find the questions their answer operands name: by UUID, or by path relative to the
// version, which is how conditions written by hand address them; and all of them, in the version's order
export interface QuestionIndex {
  find: (reference: string) => SerializedNode | undefined;
  questions: SerializedNode[];
}

export function indexQuestions(version: SerializedNode): QuestionIndex {
  const root = `${String(version["@path"])}/`;
  const byReference = new Map<string, SerializedNode>();
  const questions: SerializedNode[] = [];
  const visit = (node: SerializedNode) => partsOf(node).forEach(part => {
    if (isQuestion(part)) {
      questions.push(part);
      byReference.set(String(part["jcr:uuid"]), part);
      byReference.set(String(part["@path"]).replace(root, ""), part);
    }
    visit(part);
  });
  visit(version);
  return { find: reference => byReference.get(reference), questions };
}
