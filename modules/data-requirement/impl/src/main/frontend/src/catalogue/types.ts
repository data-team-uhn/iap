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

// The catalogue as the browser holds it: databases, holding collections, holding fields. Nothing in
// this directory knows where a catalogue comes from.

export const CARDINALITY_VALUES = [ "0..1", "1..1", "0..*", "1..*" ] as const;

export type Cardinality = (typeof CARDINALITY_VALUES)[number];

export interface CatalogueField {
  // `${database}/${collection}/${identifier}`, unique across the catalogue. A selection records these
  key: string;
  // The source system's own name for the field
  identifier: string;
  // What a reader is shown
  label: string;
  // True when the label fell back to the identifier because nothing curated one
  labelIsFallback: boolean;
  // Empty if the catalogue gives none
  description: string;
  // The notation as the catalogue wrote it. Only those in {@link CARDINALITY_VALUES} have words; any
  // other is shown bare
  cardinality: string;
  // The field's type in the source system
  dataType: string;
  // Whether the field can identify a person. `undefined` if the catalogue does not say, which is not
  // `false`: nobody has assessed the field. Neither is flagged
  phi?: boolean;
  // A sample value, or `undefined` if the catalogue gives none
  example?: string;
}

export interface CatalogueCollection {
  // Unique across the catalogue: `${database}/${identifier}`
  key: string;
  identifier: string;
  label: string;
  fields: CatalogueField[];
}

export interface CatalogueDatabase {
  identifier: string;
  label: string;
  description: string;
  collections: CatalogueCollection[];
  // How many fields its collections hold
  fieldCount: number;
}

export interface Catalogue {
  databases: CatalogueDatabase[];
  totalFields: number;
  totalCollections: number;
}

/** A catalogue holding nothing, which is what a panel shows before one has been loaded. */
export const EMPTY_CATALOGUE: Catalogue = { databases: [], totalFields: 0, totalCollections: 0 };
