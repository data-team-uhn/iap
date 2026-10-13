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

import { type ChangeObject, diffLines, diffWordsWithSpace } from "diff";

import { isNode } from "../serializedNode";

// What two snapshots of the same content differ in, as plain data for any page to show. No React, no fetch.

export interface TextPart {
  text: string;
  // Whether these are words that changed within their line. A line removed or added whole has none.
  changed: boolean;
}

export interface TextLine {
  change: "unchanged" | "removed" | "added";
  parts: TextPart[];
}

// The lines of a chunk of text, which ends with a line break
const linesOf = (text: string): string[] => text.replace(/\n$/, "").split("\n");

// Adds a final line break where there is none, so that a last line still matches the same line followed by others
const ended = (text: string): string => (text === "" || text.endsWith("\n") ? text : `${text}\n`);

const whole = (change: TextLine["change"], text: string): TextLine => ({ change, parts: [ { text, changed: false } ] });

// A removed line and the line that replaced it, each with the words that changed marked. When the two have no words
// in common, nothing is marked.
function pairOf(removed: string, added: string): [ TextLine, TextLine ] {
  const words = diffWordsWithSpace(removed, added);
  if (!words.some(word => !word.added && !word.removed && word.value.trim() !== "")) {
    return [ whole("removed", removed), whole("added", added) ];
  }
  const side = (left: (word: ChangeObject<string>) => boolean, change: "removed" | "added"): TextLine => ({
    change,
    parts: words.filter(word => !left(word)).map(word => ({ text: word.value, changed: word.added || word.removed })),
  });
  return [ side(word => word.added, "removed"), side(word => word.removed, "added") ];
}

// Compares two texts line by line. The lines that replaced others are paired in order, and the words that changed
// within each pair are marked. A run of changed lines lists the removed ones first, then the added ones.
export function compareText(before: string, after: string): TextLine[] {
  const lines: TextLine[] = [];
  const chunks = diffLines(ended(before), ended(after));
  for (let at = 0; at < chunks.length; at++) {
    const chunk = chunks[at];
    if (!chunk.added && !chunk.removed) {
      lines.push(...linesOf(chunk.value).map(text => whole("unchanged", text)));
      continue;
    }
    const removed = chunk.removed ? linesOf(chunk.value) : [];
    const replacing = chunk.removed && chunks.at(at + 1)?.added === true;
    const added = chunk.added ? linesOf(chunk.value) : replacing ? linesOf(chunks[++at].value) : [];
    const pairs = removed.slice(0, added.length).map((line, index) => pairOf(line, added[index]));
    lines.push(
      ...pairs.map(([ old ]) => old), ...removed.slice(pairs.length).map(text => whole("removed", text)),
      ...pairs.map(([ , now ]) => now), ...added.slice(pairs.length).map(text => whole("added", text)),
    );
  }
  return lines;
}

export interface FieldDifference {
  field: string;
  change: "added" | "removed" | "changed";
  before?: unknown;
  after?: unknown;
}

// Whether a value counts as no value, as an emptied field does: missing, null, an empty text, or an empty list
const isEmpty = (value: unknown): boolean =>
  value === undefined || value === null || value === "" || (Array.isArray(value) && value.length === 0);

// A field's value in a snapshot. A serialized node lists its children beside its properties, so a child of the same
// name is not taken for a value.
const valueIn = (snapshot: Record<string, unknown> | undefined, field: string): unknown => {
  const value = snapshot?.[field];
  return isNode(value) ? undefined : value;
};

// The fields asked about that differ between two snapshots, in the order asked. Two lists are the same only with the
// same items in the same order.
export function compareFields(before: Record<string, unknown> | undefined, after: Record<string, unknown> | undefined,
  fields: string[]): FieldDifference[] {
  return fields.flatMap((field): FieldDifference[] => {
    const old = valueIn(before, field);
    const now = valueIn(after, field);
    if (isEmpty(old)) {
      return isEmpty(now) ? [] : [ { field, change: "added", after: now } ];
    }
    if (isEmpty(now)) {
      return [ { field, change: "removed", before: old } ];
    }
    return JSON.stringify(old) === JSON.stringify(now) ? [] : [ { field, change: "changed", before: old, after: now } ];
  });
}
