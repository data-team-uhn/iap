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
 * The selected fields, grouped by collection in catalogue order, so the panel lists them as the tree
 * does. A key the catalogue does not offer is left out.
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

/** How many of the chosen fields the catalogue flagged as identifying a person. */
export function countPhiFields(groups: SelectionGroup[]): number {
  return groups.reduce((total, group) => total + group.fields.filter(field => field.phi).length, 0);
}
