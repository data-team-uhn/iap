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

import { type JcrNode, nameOf } from "./schemaModel";

const PART_SUPERTYPES = [ "sch/Requirement", "sch/FormItem" ];

export const OPTION_TYPE = "sch/AnswerOption";

const isObject = (value: unknown): value is JcrNode => typeof value === "object" && value !== null
  && !Array.isArray(value);

const text = (node: JcrNode, key: string): string | undefined => {
  const value = node[key];
  return typeof value === "string" && value.trim() !== "" ? value : undefined;
};

const number = (node: JcrNode, key: string): number | undefined => {
  const value = node[key];
  return typeof value === "number" ? value : undefined;
};

export const strings = (value: unknown): string[] => (Array.isArray(value) ? value : [ value ])
  .filter((item): item is string | number => typeof item === "string" || typeof item === "number")
  .map(String);

export const resourceTypeOf = (node: JcrNode): string => String(node["sling:resourceType"]);

export const partsOf = (node: JcrNode): JcrNode[] => Object.values(node)
  .filter(isObject)
  .filter(child => PART_SUPERTYPES.includes(String(child["sling:resourceSuperType"])));

export const optionsOf = (question: JcrNode): JcrNode[] => Object.values(question)
  .filter(isObject)
  .filter(child => resourceTypeOf(child) === OPTION_TYPE);

export const conditionOf = (part: JcrNode): JcrNode | undefined => {
  const condition = part["cond:condition"];
  return isObject(condition) ? condition : undefined;
};

// What a part is called where it is shown: a requirement's label, a section's title, a question's text
export const headingOf = (part: JcrNode): string =>
  text(part, "text") ?? text(part, "label") ?? text(part, "title") ?? nameOf(part);

export const detailOf = (node: JcrNode, key: string): string | undefined => text(node, key);

export const optionLabelOf = (option: JcrNode): string => text(option, "label") ?? strings(option.value).at(0) ?? "";

// What a part or an option is called where it is shown
export const shownNameOf = (node: JcrNode): string =>
  resourceTypeOf(node) === OPTION_TYPE ? optionLabelOf(node) : headingOf(node);

const DATA_TYPES: Record<string, string> = {
  text: "Text",
  long: "Whole number",
  double: "Number",
  boolean: "Yes or no",
  date: "Date",
  file: "File",
};

export const dataTypeOf = (question: JcrNode): string => {
  const dataType = text(question, "dataType") ?? "text";
  return DATA_TYPES[dataType] ?? dataType;
};

// How many answers a question takes, in words: a positive minimum makes it required, and a maximum other
// than one lets it take several, with zero or less meaning no limit
export function answerCountOf(question: JcrNode): string[] {
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
export function boundsOf(question: JcrNode): string | undefined {
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
// version, which is how conditions written by hand address them
export interface QuestionIndex {
  find: (reference: string) => JcrNode | undefined;
}

export function indexQuestions(version: JcrNode): QuestionIndex {
  const root = `${String(version["@path"])}/`;
  const byReference = new Map<string, JcrNode>();
  const visit = (node: JcrNode) => partsOf(node).forEach(part => {
    if (resourceTypeOf(part) === "sch/Question") {
      byReference.set(String(part["jcr:uuid"]), part);
      byReference.set(String(part["@path"]).replace(root, ""), part);
    }
    visit(part);
  });
  visit(version);
  return { find: reference => byReference.get(reference) };
}
