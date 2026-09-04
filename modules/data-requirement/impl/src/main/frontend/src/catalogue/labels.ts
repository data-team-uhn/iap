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

// What a reader is shown for a thing the source system named. Applied where the catalogue is built
// rather than where it is drawn, so that searching, the selection panel and an accessible name all
// agree on one string — and the source's own name stays on the model, and stays searchable, even
// when it is not what appears.

// A run of letters that is Title Case and nothing more: `Birth` yes, `MRN` and `ICD10` no. That
// shape is what makes acronyms and type names safe without a list naming them.
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

// Puts the configured spelling back wherever the lowering passed over a word somebody said to keep.
//
// One pass per word, each putting back its own spelling, so there is no lookup that could come up
// empty and no index arithmetic to get wrong. Applied to the lowered text rather than the original
// because lowering changes case and never length, so the same runs are still there to be found.
//
// Longest first, so a phrase is put back before a word inside it.
function restorePreserved(cased: string, words: string[]): string {
  return [ ...words ]
    .sort((a, b) => b.length - a.length)
    .reduce((text, word) =>
      text.replace(new RegExp(`\\b${escapeForRegExp(word)}\\b`, "gi"), word), cased);
}

/**
 * Lowers a curated Title Case label to sentence case, leaving the first word capitalised.
 *
 * Curation is written in Title Case, and the collection names above it are sentence case, so a tree
 * of *Allergy intolerance* › *Allergy Code (description)* disagrees with itself. It matters beyond
 * tidiness: in Title Case every word carries the same weight, where in sentence case a capital means
 * the word is a name.
 *
 * Acronyms and type names survive by their shape. The ones that do not — a product, a defined term —
 * are named in `preserveWords`, which is a deployment's own vocabulary rather than the platform's.
 */
export function sentenceCaseLabel(label: string, preserveWords: string[]): string {
  const trimmed = label.trim();
  let seenLeadingRun = false;
  const cased = trimmed.replace(/[A-Za-z]+/g, run => {
    // The first run keeps its capital — that is what makes it a sentence
    if (!seenLeadingRun) {
      seenLeadingRun = true;
      return run;
    }
    return isTitleCased(run) ? run.toLowerCase() : run;
  });
  return restorePreserved(cased, preserveWords);
}

/** What to show for an identifier nothing curated a label for. */
export function humaniseIdentifier(identifier: string, overrides: Record<string, string>): string {
  const trimmed = identifier.trim();
  if (!trimmed) {
    return "";
  }
  return overrides[trimmed] ?? toSentenceCase(trimmed);
}

/**
 * What to show for one field, and whether it is the source's own name for want of anything better.
 *
 * @param curated the label the catalogue carries, if any
 * @param identifier the source system's own name for the field
 * @param config how much of a field this catalogue shows
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
 * What to show for one collection.
 *
 * There is no table of correct spellings here, unlike the application this came from: a catalogue is
 * authored content, so whoever wrote it spelled the collection the way they meant it. All that is
 * left is turning a bare `AllergyIntolerance` into something that reads as English.
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
