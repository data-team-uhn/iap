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

import type { Catalogue, CatalogueField } from "./types";

// What a selection amounts to: the chosen fields, gathered under the collections they came from.

export interface SelectionGroup {
  databaseIdentifier: string;
  databaseLabel: string;
  collectionIdentifier: string;
  collectionLabel: string;
  fields: CatalogueField[];
}

/**
 * The selected fields, in the order the catalogue puts them.
 *
 * Walked from the catalogue rather than from the set of keys, so that the panel lists a selection in
 * the same order the tree offered it. A reader comparing the two is doing so to check their own
 * work, and two orderings would make that harder than reading either alone.
 */
export function groupSelection(catalogue: Catalogue, selected: ReadonlySet<string>): SelectionGroup[] {
  if (selected.size === 0) {
    return [];
  }
  return catalogue.databases.flatMap(database =>
    database.collections
      .map(collection => ({
        databaseIdentifier: database.identifier,
        databaseLabel: database.label,
        collectionIdentifier: collection.identifier,
        collectionLabel: collection.label,
        fields: collection.fields.filter(field => selected.has(field.key)),
      }))
      .filter(group => group.fields.length > 0));
}

/** How many fields a grouped selection holds. */
export function countFields(groups: SelectionGroup[]): number {
  return groups.reduce((total, group) => total + group.fields.length, 0);
}

/**
 * How many of the chosen fields the catalogue flagged as identifying a person.
 *
 * Beside {@link groupSelection} because more than one place quotes this number at a submitter, and
 * they have to be demonstrably the same count rather than two that agree today.
 *
 * A field nobody assessed is not flagged — see {@link CatalogueField.phi} for why that is not the
 * same as one assessed and found clear.
 */
export function countPhiFields(groups: SelectionGroup[]): number {
  return groups.reduce((total, group) => total + group.fields.filter(field => field.phi).length, 0);
}
