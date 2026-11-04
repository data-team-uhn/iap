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

import type { DisplayConfig } from "./display";

// What a reader is shown for a thing the source system named. Labels are computed as the catalogue is
// read, so the tree, the search and the selection panel all show the same string.

// A run of letters that is Title Case and nothing more: `Birth` yes, `MRN` and `ICD10` no. Acronyms
// and type names keep their case by this shape alone.
function isTitleCased(word: string): boolean {
  return /^[A-Z][a-z]+$/.test(word);
}

/** `birthDate` and `birth date` alike become `Birth date`. */
export function toSentenceCase(value: string): string {
  const spaced = value
    .trim()
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function escapeForRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// Puts back the configured spelling of each word to keep. Longest first, so a phrase is restored
// before a word inside it.
function restorePreserved(cased: string, words: string[]): string {
  return [ ...words ]
    .sort((a, b) => b.length - a.length)
    .reduce((text, word) =>
      text.replace(new RegExp(`\\b${escapeForRegExp(word)}\\b`, "gi"), word), cased);
}

/**
 * Lowers a curated Title Case label to sentence case, leaving the first word capitalised. Acronyms
 * and type names keep their case by their shape; any other word to keep, such as a product name, is
 * listed in `preserveWords`.
 */
export function sentenceCaseLabel(label: string, preserveWords: string[]): string {
  const trimmed = label.trim();
  let seenLeadingRun = false;
  const cased = trimmed.replace(/[A-Za-z]+/g, run => {
    if (!seenLeadingRun) {
      seenLeadingRun = true;
      return run;
    }
    return isTitleCased(run) ? run.toLowerCase() : run;
  });
  return restorePreserved(cased, preserveWords);
}

/** What to show for an identifier the catalogue gives no label for; empty if the identifier is blank. */
export function humaniseIdentifier(identifier: string, overrides: Record<string, string>): string {
  const trimmed = identifier.trim();
  if (!trimmed) {
    return "";
  }
  return overrides[trimmed] ?? toSentenceCase(trimmed);
}

/**
 * What to show for one field, and whether it was derived from the identifier for want of a label.
 *
 * @param curated the label the catalogue gives the field, or `undefined` if it gives none
 * @param identifier the source system's own name for the field, such as `birthDate`
 * @param config the display config, whose `tree.field` flags decide the casing
 */
export function fieldLabel(curated: string | undefined, identifier: string, config: DisplayConfig):
{ label: string; labelIsFallback: boolean } {
  const given = curated?.trim();
  if (given) {
    return {
      label: config.tree.field.sentenceCaseLabels
        ? sentenceCaseLabel(given, config.preserveWords)
        : given,
      labelIsFallback: false,
    };
  }
  return {
    label: config.tree.field.humaniseIdentifierWhenNoCommonName
      ? humaniseIdentifier(identifier, config.labelOverrides)
      : identifier.trim(),
    labelIsFallback: true,
  };
}

/**
 * What to show for one collection: its curated label, or else its identifier split into words. A
 * label that only repeats the identifier counts as none.
 */
export function collectionLabel(curated: string | undefined, identifier: string,
  config: DisplayConfig): string {
  const given = curated?.trim();
  if (given && given !== identifier) {
    return config.tree.field.sentenceCaseLabels
      ? sentenceCaseLabel(given, config.preserveWords)
      : given;
  }
  return toSentenceCase(identifier);
}
