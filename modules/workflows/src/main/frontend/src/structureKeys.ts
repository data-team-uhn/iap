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

// The records of a repeated structure the server drops for their key.

import { settled, textValues, valuesOf } from "./parameterValues";
import { hasSingleValuedKey } from "./workflowTypes";

import type { StructureRecord } from "./parameterValues";
import type { Parameter } from "./workflowTypes";

export type KeyProblem = "blank" | "repeated";

// The key of a record, trimmed as the server compares it.
export function keyOf(parameter: Parameter, record: StructureRecord): string {
  const key = parameter.keyParameter === undefined ? undefined
    : textValues(valuesOf(record, parameter.keyParameter)).at(0);
  return (key ?? "").trim();
}

// What is wrong with the key of each record, in order. The server keeps the first record of a key and drops
// a later one, and drops a record that has content but no key. A record with nothing in it is a draft, not a
// problem. A structure with no single-valued key field has no key to be wrong: the server stores none of it.
export function keyProblems(parameter: Parameter, records: readonly StructureRecord[]): (KeyProblem | undefined)[] {
  const keys = records.map(record => keyOf(parameter, record));
  return records.map((record, index): KeyProblem | undefined => {
    if (!hasSingleValuedKey(parameter)) {
      return undefined;
    }
    if (keys[index] === "") {
      return settled([ record ]).length > 0 ? "blank" : undefined;
    }
    return keys.indexOf(keys[index]) < index ? "repeated" : undefined;
  });
}
