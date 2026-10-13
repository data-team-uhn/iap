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

// Suggests a name for new content before it is created, made from what it says. The name keeps to the rule of the
// workflow creating it, which comes with what may be created. Names are made from text as the server's createContent
// makes them, and the tests of both use the same examples. No React, no fetch.

import type { CreatableType } from "./fieldsModel";

// How many words of a text make a name
const NAME_WORDS = 5;

// The first words of a text, without accents, camel-cased
function camelCase(text: string): string {
  return text.normalize("NFD").replace(/\p{M}/gu, "").split(/[^\p{L}\p{N}]+/u)
    .filter(word => word !== "")
    .slice(0, NAME_WORDS)
    .map((word, at) => {
      const lower = word.toLowerCase();
      return at === 0 ? lower : lower.charAt(0).toUpperCase() + lower.slice(1);
    })
    .join("");
}

// The name itself if it is free, or else the first free one of name2, name3, and so on
function freeName(base: string, taken: string[]): string {
  let name = base;
  for (let count = 2; taken.includes(name); count++) {
    name = `${base}${count}`;
  }
  return name;
}

// A free name for new content of a type, made from the text of its first field. When that text makes no name the
// type may take, the name comes from the type instead.
export function suggestName(text: unknown, type: CreatableType, taken: string[]): string {
  // Whole, as the server matches it
  const pattern = type.namePattern === undefined ? undefined : new RegExp(`^(?:${type.namePattern})$`, "u");
  const derived = typeof text === "string" ? camelCase(text) : "";
  const allowed = derived !== "" && (pattern?.test(derived) ?? true);
  return freeName(allowed ? derived : type.defaultName ?? "", taken);
}
