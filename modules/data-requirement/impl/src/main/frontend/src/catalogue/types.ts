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

// The catalogue as the browser holds it: three levels, database to collection to field. Built from
// what the server serves rather than parsed here, so nothing in this directory knows where a
// catalogue comes from.

export const CARDINALITY_VALUES = [ "0..1", "1..1", "0..*", "1..*" ] as const;

export type Cardinality = (typeof CARDINALITY_VALUES)[number];

export interface CatalogueField {
  // Unique across the whole catalogue: `${database}/${collection}/${identifier}`. This is what a
  // selection records, and what it means has to survive a catalogue being republished
  key: string;
  // The source system's own name for the field
  identifier: string;
  // What a reader is shown
  label: string;
  // True when the label fell back to the identifier because nothing curated one
  labelIsFallback: boolean;
  description: string;
  // Any string, not just the four below: a catalogue may say something this vocabulary has no words
  // for, and an unrecognised value is passed through rather than dropped. {@link CARDINALITY_VALUES}
  // is what `describeCardinality` can actually say something about
  cardinality: string;
  // The field's type in the source system. Kept on the model and deliberately not rendered: it is
  // what sub-field expansion would key on, and showing it now would be noise
  dataType: string;
  // Whether the field holds information that can identify a person. `undefined` rather than `false`
  // where the catalogue says nothing, so that a source nobody has assessed cannot be mistaken for
  // one assessed and found clear. Every consumer treats both as not-flagged; the distinction is for
  // whoever reads the model
  phi?: boolean;
  // A sample value, `undefined` where the catalogue gives none
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
  // Every field beneath it, counted once so the header does not walk the tree to say how many
  fieldCount: number;
}

export interface Catalogue {
  databases: CatalogueDatabase[];
  totalFields: number;
  totalCollections: number;
}

/** A catalogue holding nothing, which is what a panel shows before one has been loaded. */
export const EMPTY_CATALOGUE: Catalogue = { databases: [], totalFields: 0, totalCollections: 0 };
