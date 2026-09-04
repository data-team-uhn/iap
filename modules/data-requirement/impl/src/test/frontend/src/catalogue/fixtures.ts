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

import type {
  Catalogue,
  CatalogueCollection,
  CatalogueDatabase,
  CatalogueField,
} from "@iap/data-requirement/catalogue/types";

// A small catalogue the suites share, built the way the loader builds one so that a test reads the
// same shape the application does.

export function field(collectionKey: string, identifier: string,
  overrides: Partial<CatalogueField> = {}): CatalogueField {
  return {
    key: `${collectionKey}/${identifier}`,
    identifier,
    label: identifier,
    labelIsFallback: true,
    description: "",
    cardinality: "0..1",
    dataType: "string",
    ...overrides,
  };
}

export function collection(databaseIdentifier: string, identifier: string,
  fields: string[], overrides: Partial<CatalogueField> = {}): CatalogueCollection {
  const key = `${databaseIdentifier}/${identifier}`;
  return {
    key,
    identifier,
    label: identifier,
    fields: fields.map(name => field(key, name, overrides)),
  };
}

export function database(identifier: string, collections: CatalogueCollection[]): CatalogueDatabase {
  return {
    identifier,
    label: identifier,
    description: "",
    collections,
    fieldCount: collections.reduce((total, each) => total + each.fields.length, 0),
  };
}

export function catalogue(databases: CatalogueDatabase[]): Catalogue {
  return {
    databases,
    totalFields: databases.reduce((total, each) => total + each.fieldCount, 0),
    totalCollections: databases.reduce((total, each) => total + each.collections.length, 0),
  };
}

/** Two databases, three collections, five fields — enough for grouping and counting to mean something. */
export function sampleCatalogue(): Catalogue {
  return catalogue([
    database("records", [
      collection("records", "Patient", [ "birthDate", "gender" ]),
      collection("records", "Encounter", [ "period" ]),
    ]),
    database("registry", [
      collection("registry", "Consent", [ "status", "dateGiven" ]),
    ]),
  ]);
}
