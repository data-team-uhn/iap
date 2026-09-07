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

// Reading a set of keys back against a catalogue. A selection is stored as keys and nothing else, so
// this is the whole of what turns one back into fields.

/** Every key this catalogue offers. */
export function knownFieldKeys(catalogue: Catalogue): Set<string> {
  return new Set(allFields(catalogue).map(field => field.key));
}

/** Every field in the catalogue, flattened out of the database and collection levels. */
export function allFields(catalogue: Catalogue): CatalogueField[] {
  return catalogue.databases.flatMap(database =>
    database.collections.flatMap(collection => collection.fields));
}

/**
 * The keys this catalogue does not offer.
 *
 * Inside a submission this is information rather than a repair: a selection is read against the
 * version it was made from, so nothing goes missing from it. Comparing against a *later* version is
 * what says how much has moved on since, which is worth telling a reader looking at an older
 * request.
 */
export function missingFrom(catalogue: Catalogue, keys: Iterable<string>): string[] {
  const known = knownFieldKeys(catalogue);
  return [ ...keys ].filter(key => !known.has(key));
}
