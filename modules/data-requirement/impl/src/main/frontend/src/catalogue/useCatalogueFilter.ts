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

import { useMemo } from "react";

import type { Catalogue, CatalogueDatabase, CatalogueField } from "./types";

// What the tree draws once a search and a set of exclusions have been applied. Kept apart from the
// tree itself because the counts a header quotes and the rows a tree renders have to be the same
// answer, and two walks of the catalogue would eventually disagree.

export interface VisibleCollection {
  key: string;
  identifier: string;
  label: string;
  /** The fields that survived the search, in catalogue order. */
  fields: CatalogueField[];
  /** Every field the collection holds, search or no search. */
  totalFieldCount: number;
}

export interface VisibleDatabase {
  database: CatalogueDatabase;
  collections: VisibleCollection[];
  /** The fields that survived the search within this database. */
  shownFieldCount: number;
}

export interface CatalogueFilterResult {
  /** The databases with something left to show, in catalogue order. */
  visible: VisibleDatabase[];
  /** The databases nobody excluded, whether or not anything in them matched. */
  includedDatabases: CatalogueDatabase[];
  shownFieldCount: number;
  /** Whether a search term or an exclusion is narrowing what is shown. */
  isFiltered: boolean;
}

// A field matches on what a reader sees and on what the source calls it alike: the identifier stays
// searchable even where it is never displayed, so `birthDate` and `date of birth` both find it
function matches(field: CatalogueField, term: string): boolean {
  return field.identifier.toLowerCase().includes(term)
    || field.label.toLowerCase().includes(term)
    || field.description.toLowerCase().includes(term);
}

// A collection nothing matched in is left out rather than shown empty: an empty group in a list of
// results reads as a result
function survivingCollections(database: CatalogueDatabase, term: string): VisibleCollection[] {
  return database.collections
    .map(collection => ({
      key: collection.key,
      identifier: collection.identifier,
      label: collection.label,
      fields: term ? collection.fields.filter(field => matches(field, term)) : collection.fields,
      totalFieldCount: collection.fields.length,
    }))
    .filter(collection => collection.fields.length > 0);
}

export function useCatalogueFilter(catalogue: Catalogue, query: string,
  excludedDatabases: ReadonlySet<string>): CatalogueFilterResult {
  return useMemo(() => {
    const term = query.trim().toLowerCase();
    const includedDatabases =
      catalogue.databases.filter(database => !excludedDatabases.has(database.identifier));

    const visible = includedDatabases
      .map(database => {
        const collections = survivingCollections(database, term);
        return {
          database,
          collections,
          shownFieldCount: collections.reduce((total, each) => total + each.fields.length, 0),
        };
      })
      .filter(database => database.collections.length > 0);

    return {
      visible,
      includedDatabases,
      shownFieldCount: visible.reduce((total, each) => total + each.shownFieldCount, 0),
      isFiltered: term.length > 0 || excludedDatabases.size > 0,
    };
  }, [ catalogue, query, excludedDatabases ]);
}
