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

// How much of each field the catalogue shows. The defaults live in `displayConfig.json`, and a host
// overrides only what it needs.

export interface FieldDisplayConfig {
  /** The source's own name for the field, in the code style beside the label. */
  showIdentifier: boolean;
  /** When the catalogue gives no label, turn `birthDate` into `Birth date` rather than showing it raw. */
  humaniseIdentifierWhenNoCommonName: boolean;
  /** Turn the catalogue's Title Case labels into sentence case: `Date of Birth` becomes `Date of birth`. */
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
  /** Labels for identifiers that humanise badly, keyed by the source's own name. */
  labelOverrides: Record<string, string>;
  /** Words and phrases sentence-casing must not lower, such as product names. */
  preserveWords: string[];
}

/** What a host may override: any part, at any depth. */
export interface DisplayOverride {
  tree?: {
    field?: Partial<FieldDisplayConfig>;
    collection?: Partial<CollectionDisplayConfig>;
  };
  labelOverrides?: Record<string, string>;
  preserveWords?: string[];
}

// The JSON file as written, `_comment` keys included
const AUTHORED_DEFAULTS = defaults as unknown as DisplayConfig;

// Drops the `_comment` keys the JSON carries for whoever edits it
function withoutNotes<T extends object>(value: T): T {
  return Object.fromEntries(
    Object.entries(value).filter(([ key ]) => !key.startsWith("_"))) as T;
}

const DEFAULT_FIELD = withoutNotes(AUTHORED_DEFAULTS.tree.field);

const DEFAULT_COLLECTION = withoutNotes(AUTHORED_DEFAULTS.tree.collection);

/**
 * The defaults with a host's overrides laid over them flag by flag, so a host can override one flag
 * alone.
 */
export function resolveDisplayConfig(override?: DisplayOverride): DisplayConfig {
  return {
    tree: {
      field: { ...DEFAULT_FIELD, ...override?.tree?.field },
      collection: { ...DEFAULT_COLLECTION, ...override?.tree?.collection },
    },
    labelOverrides: { ...AUTHORED_DEFAULTS.labelOverrides, ...override?.labelOverrides },
    preserveWords: override?.preserveWords ?? AUTHORED_DEFAULTS.preserveWords,
  };
}

/** What a host gets when it overrides nothing. */
export const defaultDisplayConfig: DisplayConfig = resolveDisplayConfig();
