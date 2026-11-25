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

import { childrenOfPrimaryType, stringsOf, textOf, type SerializedNode } from "@iap/frontend-commons/serializedNode";

// Reading catalogues and their versions from their JSON serialization, and the pages that show them.
// Where each stands is its lifecycle tags. What may be done with it is the `@events` the server offers
// on it. No React, no fetch.

export const CATALOGUES_ROOT = "/Catalogues";

// The selectors for reading catalogues and versions in a list. `simple` leaves out the repository's
// bookkeeping, and `events` adds the events the current user may send to each node. The depth stops
// above a version's databases, so a list never carries a whole catalogue.
export const listing = (depth: number): string => `${depth}.simple.events`;

export const pathOf = (node: SerializedNode): string => String(node["@path"]);

export const nameOf = (node: SerializedNode): string => String(node["@name"]);

export const tagsOf = (node: SerializedNode): string[] => stringsOf(node.tags);

export const titleOf = (catalogue: SerializedNode): string => textOf(catalogue, "title") ?? nameOf(catalogue);

export const labelOf = (version: SerializedNode): string => textOf(version, "version") ?? nameOf(version);

export const descriptionOf = (node: SerializedNode): string | undefined => textOf(node, "description");

// A catalogue's versions, in the order the catalogue keeps them
export const versionsOf = (catalogue: SerializedNode): SerializedNode[] =>
  childrenOfPrimaryType(catalogue, "datareq:CatalogueVersion");

// The page listing every catalogue
export const CATALOGUES_PAGE_URL = "/admin/catalogues";

export const cataloguePageUrl = (name: string): string => `${CATALOGUES_PAGE_URL}/${encodeURIComponent(name)}`;

// A version's page, under its catalogue's
export const versionPageUrl = (catalogueName: string, versionName: string): string =>
  `${cataloguePageUrl(catalogueName)}/${encodeURIComponent(versionName)}`;

// The names a path holds: a catalogue's, then its version's if there is one. A page's path and the
// repository's have the same shape, /admin/catalogues/registry/v2 and /Catalogues/registry/v2.
function namesIn(path: string): string[] {
  const segments = path.split("/").filter(Boolean).map(decodeURIComponent);
  return segments.slice(segments[0] === "admin" ? 2 : 1);
}

// The name of the catalogue a path leads to, such as registry
export const catalogueNameFromRoute = (path: string): string => namesIn(path)[0] ?? "";

// The name of the version a path leads to, if it leads to one, such as v2
export const versionNameFromRoute = (path: string): string | undefined => namesIn(path)[1];
