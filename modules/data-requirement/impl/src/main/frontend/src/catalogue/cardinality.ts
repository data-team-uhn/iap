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

import type { Cardinality } from "./types";

// Cardinality notation in words. The badge shows the words, and its tooltip keeps the notation.

interface CardinalityDescriptor {
  // The short form on the badge
  glyph: string;
  // The long form in its tooltip, which is also where the notation itself appears
  tip: string;
  // Whether every record has a value, which the badge emphasises
  required: boolean;
}

const CARDINALITY: Record<Cardinality, CardinalityDescriptor> = {
  "0..1": {
    glyph: "value: optional",
    tip: "Optional — this field may be empty, and holds at most one value. (0..1)",
    required: false,
  },
  "1..1": {
    glyph: "value: always",
    tip: "Always present — every record has exactly one value. (1..1)",
    required: true,
  },
  "0..*": {
    glyph: "value: optional, many",
    tip: "Optional and repeating — may be empty, or hold several values per record. (0..*)",
    required: false,
  },
  "1..*": {
    glyph: "value: always, many",
    tip: "Always present and repeating — at least one value, often several per record. (1..*)",
    required: true,
  },
};

/** How to say one cardinality in words, or `undefined` for a notation this does not recognise. */
export function describeCardinality(value: string): CardinalityDescriptor | undefined {
  return CARDINALITY[value as Cardinality];
}
