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

// Where a catalogue comes from, and how a selection is recorded. This is the seam between the
// repository and the browser under `catalogue/`, which knows neither: it is handed a catalogue and
// reports what was chosen, so the same interface serves a submission, an administrator's screen, or
// anything else that can supply the two.

import { childrenOfType, isNode, type JsonNode } from "@iap/submissions/jsonNode";

import { defaultDisplayConfig, type DisplayConfig } from "./catalogue/display";
import { collectionLabel, fieldLabel } from "./catalogue/labels";

import type {
  Catalogue,
  CatalogueCollection,
  CatalogueDatabase,
  CatalogueField,
} from "./catalogue/types";

// The resource types the serializer reports for the three levels of a catalogue version. Named here
// rather than imported from anywhere: they are what the repository says, and this is the one place
// that reads it.
const DATABASE = "datareq/Database";
const COLLECTION = "datareq/Collection";
const FIELD = "datareq/Field";

// How the three identifiers of a chosen field are joined. This has to be the separator the server
// builds a key with — `Field.getKey()` — because a selection is stored as keys and nothing else, and
// the save refuses a key the catalogue does not offer. The two spellings are a compatibility
// contract, not an implementation detail either side is free to change alone.
const KEY_SEPARATOR = "/";

// Three levels below the version — database, collection, field — which is exactly the tree and
// nothing under it. Asked for as a depth rather than as `deep`, so a field growing children later
// does not silently start pulling them over the wire.
const CATALOGUE_DEPTH = 3;

/** One property, as a string, treating anything that is not one as absent. */
function text(node: JsonNode, property: string): string {
  const value = node[property];
  return typeof value === "string" ? value : "";
}

/** One property, as a string, distinguishing absent from empty. */
function optionalText(node: JsonNode, property: string): string | undefined {
  const value = node[property];
  return typeof value === "string" && value !== "" ? value : undefined;
}

/**
 * Whether this field can identify a person: `true`, `false`, or absent where the catalogue says
 * nothing. The third answer is the point — a source nobody has assessed must not read as one
 * assessed and found clear — so a missing property stays missing rather than becoming `false`.
 */
function phi(node: JsonNode): boolean | undefined {
  const value = node.phi;
  return typeof value === "boolean" ? value : undefined;
}

function readField(node: JsonNode, databaseId: string, collectionId: string,
  config: DisplayConfig): CatalogueField | null {
  const identifier = text(node, "identifier");
  if (!identifier) {
    // A field with no identifier has no key, so nothing could ever be chosen of it. The server
    // reaches the same conclusion — `Field.getKey()` answers null — and dropping it here keeps the
    // browser's idea of what is on offer identical to what the save will accept.
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

function readCollection(node: JsonNode, databaseId: string,
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

function readDatabase(node: JsonNode, config: DisplayConfig): CatalogueDatabase | null {
  const identifier = text(node, "identifier");
  if (!identifier) {
    return null;
  }
  const collections = childrenOfType(node, COLLECTION)
    .map(collection => readCollection(collection, identifier, config))
    .filter((collection): collection is CatalogueCollection => collection !== null);
  return {
    identifier,
    // A database's label is the catalogue's own word for it. Unlike a collection's it is not derived
    // from the identifier when absent: a database is named by whoever published the catalogue, and
    // humanising `visits` into `Visits` would be inventing a name rather than reading one.
    label: text(node, "label") || identifier,
    description: text(node, "description"),
    collections,
    fieldCount: collections.reduce((total, collection) => total + collection.fields.length, 0),
  };
}

/**
 * Reads one serialized catalogue version into the model the browser holds.
 *
 * Exported separately from the fetch so that what the wire says and what the tree means are testable
 * apart — and so a caller that already has the JSON, from a page that loaded it for another reason,
 * need not ask for it twice.
 */
export function readCatalogue(node: JsonNode, config: DisplayConfig = defaultDisplayConfig): Catalogue {
  const databases = childrenOfType(node, DATABASE)
    .map(database => readDatabase(database, config))
    .filter((database): database is CatalogueDatabase => database !== null);
  return {
    databases,
    totalFields: databases.reduce((total, database) => total + database.fieldCount, 0),
    totalCollections: databases.reduce((total, database) => total + database.collections.length, 0),
  };
}

/**
 * Reads the catalogue version at a path: what a submitter may choose from.
 *
 * The path comes from the form projection rather than from the catalogue's current state, so a
 * submitter who started before a republication carries on with the version they started in.
 */
export async function fetchCatalogue(path: string,
  config: DisplayConfig = defaultDisplayConfig): Promise<Catalogue> {
  const response = await fetch(`${path}.${CATALOGUE_DEPTH}.json`);
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
 * Records which fields were chosen, as a `saveDataSelection` event on the submission.
 *
 * Choosing is a workflow step for the same reason answering is: a submitter can read their own
 * submission and nothing more, so there is no path by which they could write a selection themselves,
 * and what may be chosen and until when is the handler's answer rather than a permission on a
 * folder. A refusal therefore arrives as the engine's own reason.
 *
 * The whole selection is sent rather than what changed. Ticking a collection and clearing the panel
 * both move many fields at once, so a protocol of additions and removals would need a vocabulary for
 * that; sending what the selection now is says the same thing without one. Sending nothing is how a
 * selection is cleared, and the handler reads it that way.
 *
 * The event is named by a *selector*, which is why `.json` follows it: in `<path>.saveDataSelection`
 * Sling reads the last dot-separated token as the extension, so the event name would arrive as a
 * format and the POST would mean `save` instead.
 */
export async function saveDataSelection(path: string, requirement: string,
  fields: readonly string[]): Promise<void> {
  const body = new URLSearchParams();
  body.append("requirement", requirement);
  // Repeated rather than joined, which is what the handler reads back as a multi-valued property —
  // and what keeps a key containing the separator from being read as two
  fields.forEach(field => body.append("fields", field));
  const response = await fetch(`${path}.saveDataSelection.json`, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This selection could not be saved (${response.status})`);
  }
}
