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

// What the tree draws once a search and the excluded databases are applied. Its counts and its rows
// come from the same walk of the catalogue.

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
  /** Whether a search term is narrowing what is shown. */
  searching: boolean;
  /** Whether every database of a catalogue that has some was excluded. */
  noDatabasesIncluded: boolean;
}

// What a search looks in, lowercased once per field rather than on every keystroke. A field matches on
// its identifier, label or description, so the identifier finds it even where it is not shown.
const haystacks = new WeakMap<CatalogueField, string>();

function haystackOf(field: CatalogueField): string {
  let haystack = haystacks.get(field);
  if (haystack === undefined) {
    haystack = `${field.identifier}\n${field.label}\n${field.description}`.toLowerCase();
    haystacks.set(field, haystack);
  }
  return haystack;
}

// During a search, leaves out a collection with no matching field
function survivingCollections(database: CatalogueDatabase, term: string): VisibleCollection[] {
  return database.collections
    .map(collection => ({
      key: collection.key,
      identifier: collection.identifier,
      label: collection.label,
      fields: term ? collection.fields.filter(field => haystackOf(field).includes(term)) : collection.fields,
      totalFieldCount: collection.fields.length,
    }))
    .filter(collection => !term || collection.fields.length > 0);
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
      .filter(database => !term || database.collections.length > 0);
    return {
      visible,
      searching: term.length > 0,
      noDatabasesIncluded: catalogue.databases.length > 0 && includedDatabases.length === 0,
    };
  }, [ catalogue, query, excludedDatabases ]);
}
