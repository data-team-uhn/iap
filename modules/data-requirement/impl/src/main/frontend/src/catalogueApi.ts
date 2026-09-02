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

// Reads a catalogue version from the repository, and records a selection made from it. The browser
// under `catalogue/` knows neither: it is handed a catalogue and reports what was chosen.

import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { childrenOfType, isNode, type SerializedNode } from "@iap/frontend-commons/serializedNode";

import { defaultDisplayConfig, type DisplayConfig } from "./catalogue/display";
import { collectionLabel, fieldLabel } from "./catalogue/labels";

import type {
  Catalogue,
  CatalogueCollection,
  CatalogueDatabase,
  CatalogueField,
} from "./catalogue/types";

// The resource types of the three levels of a catalogue version
const DATABASE = "datareq/Database";
const COLLECTION = "datareq/Collection";
const FIELD = "datareq/Field";

// Joins the three identifiers of a field into its key. It must match `Field.getKey()` on the server,
// which refuses to save a key the catalogue does not offer.
const KEY_SEPARATOR = "/";

// Database, collection and field: the three levels below a version. A fixed depth rather than
// `deep`, so children a field gains later are not fetched.
const CATALOGUE_DEPTH = 3;

/** A string property, or an empty string if it is absent or not a string. */
function text(node: SerializedNode, property: string): string {
  const value = node[property];
  return typeof value === "string" ? value : "";
}

/** A string property, or `undefined` if it is absent, empty or not a string. */
function optionalText(node: SerializedNode, property: string): string | undefined {
  const value = node[property];
  return typeof value === "string" && value !== "" ? value : undefined;
}

/** Whether the field can identify a person, or `undefined` if the catalogue does not say. */
function phi(node: SerializedNode): boolean | undefined {
  const value = node.phi;
  return typeof value === "boolean" ? value : undefined;
}

function readField(node: SerializedNode, databaseId: string, collectionId: string,
  config: DisplayConfig): CatalogueField | null {
  const identifier = text(node, "identifier");
  if (!identifier) {
    // Without an identifier a field has no key, on the server either, so it cannot be chosen
    return null;
  }
  const { label, labelIsFallback } = fieldLabel(optionalText(node, "label"), identifier, config);
  return {
    key: databaseId + KEY_SEPARATOR + collectionId + KEY_SEPARATOR + identifier,
    identifier,
    label,
    labelIsFallback,
    description: text(node, "description"),
    cardinality: text(node, "cardinality"),
    dataType: text(node, "dataType"),
    phi: phi(node),
    example: optionalText(node, "example"),
  };
}

function readCollection(node: SerializedNode, databaseId: string,
  config: DisplayConfig): CatalogueCollection | null {
  const identifier = text(node, "identifier");
  if (!identifier) {
    return null;
  }
  return {
    key: databaseId + KEY_SEPARATOR + identifier,
    identifier,
    label: collectionLabel(optionalText(node, "label"), identifier, config),
    fields: childrenOfType(node, FIELD)
      .map(field => readField(field, databaseId, identifier, config))
      .filter((field): field is CatalogueField => field !== null),
  };
}

function readDatabase(node: SerializedNode, config: DisplayConfig): CatalogueDatabase | null {
  const identifier = text(node, "identifier");
  if (!identifier) {
    return null;
  }
  const collections = childrenOfType(node, COLLECTION)
    .map(collection => readCollection(collection, identifier, config))
    .filter((collection): collection is CatalogueCollection => collection !== null);
  return {
    identifier,
    // An unlabelled database shows its identifier as written; unlike a collection's, it is not
    // humanised
    label: text(node, "label") || identifier,
    description: text(node, "description"),
    collections,
    fieldCount: collections.reduce((total, collection) => total + collection.fields.length, 0),
  };
}

/** Reads a serialized catalogue version into the model the browser holds. */
export function readCatalogue(node: SerializedNode, config: DisplayConfig = defaultDisplayConfig): Catalogue {
  const databases = childrenOfType(node, DATABASE)
    .map(database => readDatabase(database, config))
    .filter((database): database is CatalogueDatabase => database !== null);
  return {
    databases,
    totalFields: databases.reduce((total, database) => total + database.fieldCount, 0),
    totalCollections: databases.reduce((total, database) => total + database.collections.length, 0),
  };
}

/** Loads the catalogue version at a repository path into the model the browser holds. */
export async function fetchCatalogue(doFetch: AuthenticatedFetch, path: string,
  config: DisplayConfig = defaultDisplayConfig): Promise<Catalogue> {
  const response = await doFetch(`${path}.${CATALOGUE_DEPTH}.json`);
  if (!response.ok) {
    throw new Error(`This catalogue could not be loaded (${response.status})`);
  }
  const body: unknown = await response.json();
  if (!isNode(body)) {
    throw new Error("This catalogue could not be read");
  }
  return readCatalogue(body, config);
}

/**
 * Records which fields were chosen, as a `saveDataSelection` event on the submission. A refusal
 * carries the engine's own reason.
 *
 * @param path the repository path of the submission
 * @param requirement the node name of the data requirement in the schema version
 * @param fields the keys of every chosen field, as `database/collection/field`; empty to clear them all
 */
export async function saveDataSelection(doFetch: AuthenticatedFetch, path: string, requirement: string,
  fields: readonly string[]): Promise<void> {
  const body = new URLSearchParams();
  body.append("requirement", requirement);
  // Repeated, which the handler reads as a multi-valued property
  fields.forEach(field => body.append("fields", field));
  const response = await doFetch(`${path}.saveDataSelection.json`, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This selection could not be saved (${response.status})`);
  }
}
