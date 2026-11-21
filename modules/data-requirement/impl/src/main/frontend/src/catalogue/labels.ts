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

// The labels a reader sees for the databases, collections and fields of a catalogue. Labels are
// computed once, as the catalogue is read, so the tree, the search and the selection panel all show
// the same string.

// A word that is Title Case and nothing more: `Birth` and `Médecin` yes, `MRN`, `ICD10` and `Hl7Message`
// no. Only words of this shape are lowered, so acronyms and type names keep their case.
function isTitleCased(word: string): boolean {
  return /^\p{Lu}\p{Ll}+$/u.test(word);
}

// A word in capitals throughout, such as `MRN` or `ICD10`, which humanising keeps as written
function isAllCaps(word: string): boolean {
  return word.length > 1 && /^\p{Lu}[\p{Lu}\p{N}]*$/u.test(word);
}

/** `birthDate`, `birth_date` and `birth date` alike become `Birth date`; `PATIENT_MRN` becomes `PATIENT MRN`. */
export function toSentenceCase(value: string): string {
  const words = value
    .trim()
    .replace(/([\p{Ll}\p{N}])(\p{Lu})/gu, "$1 $2")
    .replace(/(\p{Lu})(\p{Lu}\p{Ll})/gu, "$1 $2")
    .split(/[\s_-]+/)
    .filter(word => word.length > 0)
    .map(word => (isAllCaps(word) ? word : word.toLowerCase()));
  const spaced = words.join(" ");
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
 * Turns the catalogue's Title Case label into sentence case, leaving the first word capitalised. A word to
 * keep as written, such as a product name, is listed in `preserveWords`.
 */
export function sentenceCaseLabel(label: string, preserveWords: string[]): string {
  const trimmed = label.trim();
  let seenLeadingWord = false;
  const cased = trimmed.replace(/[\p{L}\p{N}]+/gu, word => {
    if (!seenLeadingWord) {
      seenLeadingWord = true;
      return word;
    }
    return isTitleCased(word) ? word.toLowerCase() : word;
  });
  return restorePreserved(cased, preserveWords);
}

/** What to show for an identifier the catalogue gives no label for; empty if the identifier is blank. */
export function humaniseIdentifier(identifier: string, overrides: Record<string, string>): string {
  const trimmed = identifier.trim();
  if (!trimmed) {
    return "";
  }
  return Object.hasOwn(overrides, trimmed) ? overrides[trimmed] : toSentenceCase(trimmed);
}

/**
 * What to show for a field or a collection, and whether it was derived from the identifier because
 * the catalogue gives no label. Both levels follow the same rule, set by the `tree.field` flags.
 *
 * @param curated the label the catalogue gives, or `undefined` if it gives none
 * @param identifier the source system's own name, such as `birthDate`
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

/** What to show for one collection. A label that only repeats the identifier counts as none. */
export function collectionLabel(curated: string | undefined, identifier: string,
  config: DisplayConfig): string {
  const given = curated?.trim();
  return fieldLabel(given === identifier ? undefined : given, identifier, config).label;
}
