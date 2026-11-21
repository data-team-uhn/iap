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

// Reads a catalogue version from the repository, and records a selection made from it.

import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { childrenOfType, textOf, type SerializedNode } from "@iap/frontend-commons/serializedNode";
import { sendEvent } from "@iap/frontend-commons/workflowEvents";

import { defaultDisplayConfig } from "./catalogue/display";
import { collectionLabel, fieldLabel } from "./catalogue/labels";

import type {
  Catalogue,
  CatalogueCollection,
  CatalogueDatabase,
  CatalogueField,
} from "./catalogue/types";

// The resource types of the three levels of a catalogue version
const DATABASE_TYPE = "datareq/Database";
const COLLECTION_TYPE = "datareq/Collection";
const FIELD_TYPE = "datareq/Field";

/**
 * The selectors that read a whole catalogue version: its databases, collections and fields. A fixed
 * depth rather than `deep`, so children a field gains later are not fetched.
 */
export const CATALOGUE_SELECTORS = "3";

/** Whether the field can identify a person, or `undefined` if the catalogue does not say. */
function phi(node: SerializedNode): boolean | undefined {
  const value = node.phi;
  return typeof value === "boolean" ? value : undefined;
}

function readField(node: SerializedNode): CatalogueField[] {
  const identifier = textOf(node, "identifier");
  // The key a selection records, as the server builds it. A field without one cannot be chosen
  const key = textOf(node, "key");
  if (!identifier || !key) {
    return [];
  }
  const { label, labelIsFallback } = fieldLabel(textOf(node, "label"), identifier, defaultDisplayConfig);
  return [ {
    key,
    identifier,
    label,
    labelIsFallback,
    description: textOf(node, "description") ?? "",
    cardinality: textOf(node, "cardinality") ?? "",
    dataType: textOf(node, "dataType") ?? "",
    phi: phi(node),
    example: textOf(node, "example"),
  } ];
}

function readCollection(node: SerializedNode, databaseId: string): CatalogueCollection[] {
  const identifier = textOf(node, "identifier");
  if (!identifier) {
    return [];
  }
  return [ {
    // Names the collection in the tree's open state, which is all the browser uses it for
    key: `${databaseId}/${identifier}`,
    identifier,
    label: collectionLabel(textOf(node, "label"), identifier, defaultDisplayConfig),
    fields: childrenOfType(node, FIELD_TYPE).flatMap(readField),
  } ];
}

function readDatabase(node: SerializedNode): CatalogueDatabase[] {
  const identifier = textOf(node, "identifier");
  if (!identifier) {
    return [];
  }
  const collections = childrenOfType(node, COLLECTION_TYPE)
    .flatMap(collection => readCollection(collection, identifier));
  return [ {
    identifier,
    // An unlabelled database shows its identifier as written; unlike a collection's, it is not
    // humanised
    label: textOf(node, "label") ?? identifier,
    description: textOf(node, "description") ?? "",
    collections,
    fieldCount: collections.reduce((total, collection) => total + collection.fields.length, 0),
  } ];
}

/** Reads a serialized catalogue version into the model the browser holds. */
export function readCatalogue(node: SerializedNode): Catalogue {
  return { databases: childrenOfType(node, DATABASE_TYPE).flatMap(readDatabase) };
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
  const params = new URLSearchParams({ requirement });
  fields.forEach(field => { params.append("fields", field); });
  await sendEvent(doFetch, path, "saveDataSelection", params);
}
