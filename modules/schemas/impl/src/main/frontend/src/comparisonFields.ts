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

import { type NamedField } from "./schemaComparisonModel";
import { childrenOf, isObject, type JcrNode, nameOf } from "./schemaModel";

// The fields a comparison looks at, as the workflows editing drafts describe them: the names they are edited under,
// and how their values read. No React, no fetch.

export interface ComparedField extends NamedField {
  // The words for a field's listed values, by value
  choices: Partial<Record<string, string>>;
  // For a reference, what it may point at: nodes of a type, under a root
  referenceType?: string;
  referenceRoot?: string;
}

const labelOf = (node: JcrNode): string => (typeof node.label === "string" ? node.label : nameOf(node));

// Every field the active versions of the given workflow definitions let an update change, the first description of
// each name kept
export function fieldsOfDefinitions(definitions: JcrNode[]): ComparedField[] {
  const found = new Map<string, ComparedField>();
  const visit = (node: JcrNode) => {
    if (isObject(node.fields)) {
      childrenOf(node.fields).filter(field => !found.has(nameOf(field))).forEach(field => found.set(nameOf(field), {
        name: nameOf(field),
        label: labelOf(field),
        choices: Object.fromEntries(isObject(field.choices)
          ? childrenOf(field.choices).map(choice => [ nameOf(choice), labelOf(choice) ]) : []),
        ...typeof field.referenceType === "string" ? { referenceType: field.referenceType } : {},
        ...typeof field.referenceRoot === "string" ? { referenceRoot: field.referenceRoot } : {},
      }));
    }
    childrenOf(node).forEach(visit);
  };
  definitions.forEach(definition => childrenOf(definition).filter(version => version.active === true).forEach(visit));
  return [ ...found.values() ];
}

// A word looked up among a lookup's own entries, and not among what every object inherits, such as "constructor"
const ownWord = (words: Partial<Record<string, string>>, key: string): string | undefined =>
  (Object.hasOwn(words, key) ? words[key] : undefined);

// How one of a field's values reads, a reference by what it points at where its name is known
export function shownValue(field: ComparedField | undefined, value: unknown,
  names: Partial<Record<string, string>> = {}): string {
  if (Array.isArray(value)) {
    return value.map(item => shownValue(field, item, names)).join(", ");
  }
  if (typeof value === "boolean") {
    return value ? "Yes" : "No";
  }
  const key = String(value);
  return ownWord(field?.choices ?? {}, key)
    ?? (field?.referenceType === undefined ? undefined : ownWord(names, key)) ?? key;
}

// Whether a change is one of text, compared word by word, rather than of a value replaced by another
export const isTextChange = (field: ComparedField | undefined, before: unknown, after: unknown): boolean =>
  Object.keys(field?.choices ?? {}).length === 0 && field?.referenceType === undefined
  && [ before, after ].every(value => value === undefined || typeof value === "string");
