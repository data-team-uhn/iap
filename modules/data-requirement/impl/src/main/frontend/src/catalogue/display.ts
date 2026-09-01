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

import defaults from "./displayConfig.json";

// How much of each field is shown is data, not code. The defaults ship with the platform; a host
// overrides what it needs and the rest is left alone, which is why this merges rather than replaces.

export interface FieldDisplayConfig {
  // The source's own name for the field, in monospace beside the label
  showIdentifier: boolean;
  // With nothing curated, turn `birthDate` into `Birth date` rather than showing it raw
  humaniseIdentifierWhenNoCommonName: boolean;
  // Lower curated Title Case to sentence case: `Date of Birth` becomes `Date of birth`
  sentenceCaseLabels: boolean;
  showCardinality: boolean;
  showType: boolean;
  showPhi: boolean;
  showDescription: boolean;
  showExample: boolean;
}

export interface CollectionDisplayConfig {
  showIdentifier: boolean;
}

export interface DisplayConfig {
  tree: {
    field: FieldDisplayConfig;
    collection: CollectionDisplayConfig;
  };
  // The source's own name to the label to show instead, for identifiers that humanise badly
  labelOverrides: Record<string, string>;
  // Words sentence-casing must not lower. A deployment's own vocabulary, so the platform ships none
  preserveWords: string[];
}

/** What a host may override — every part optional, at any depth. */
export interface DisplayOverride {
  tree?: {
    field?: Partial<FieldDisplayConfig>;
    collection?: Partial<CollectionDisplayConfig>;
  };
  labelOverrides?: Record<string, string>;
  preserveWords?: string[];
}

const RAW = defaults as unknown as DisplayConfig;

// The JSON carries `_comment` keys explaining each block to whoever edits it. They are notes to a
// person rather than part of the shape, and dropping them here is what keeps them out of everything
// downstream — including what a host sees when it reads back what it is overriding.
function withoutNotes<T extends object>(value: T): T {
  return Object.fromEntries(
    Object.entries(value).filter(([ key ]) => !key.startsWith("_"))) as T;
}

const DEFAULT_FIELD = withoutNotes(RAW.tree.field);

const DEFAULT_COLLECTION = withoutNotes(RAW.tree.collection);

/**
 * The configuration a host actually gets: the defaults, with its overrides laid over them.
 *
 * Merged one level into `tree` rather than replaced wholesale, so that overriding a single flag does
 * not silently drop the seven beside it — which is the failure a shallow merge produces and nothing
 * would report.
 */
export function resolveDisplayConfig(override?: DisplayOverride): DisplayConfig {
  return {
    tree: {
      field: { ...DEFAULT_FIELD, ...override?.tree?.field },
      collection: { ...DEFAULT_COLLECTION, ...override?.tree?.collection },
    },
    labelOverrides: { ...RAW.labelOverrides, ...override?.labelOverrides },
    preserveWords: override?.preserveWords ?? RAW.preserveWords,
  };
}

/** What a host gets when it overrides nothing. */
export const defaultDisplayConfig: DisplayConfig = resolveDisplayConfig();
