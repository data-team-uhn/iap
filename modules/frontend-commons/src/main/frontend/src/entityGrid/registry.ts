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

import type { ReactNode } from "react";

import type { EntityRow } from "./pagination";
import type { GridColDef, GridSortDirection } from "@mui/x-data-grid-pro";

// Where a column's content goes on the compact card shown in the grid's narrow-screen list
// mode: leading the card as its "title", as a "badge" beside the title (e.g. a status chip),
// on the muted "caption" line under it (several caption columns join into one " • " line),
// as a labeled "row" (the default), or nowhere ("omit").
export type EntityGridCardSlot = "title" | "badge" | "caption" | "row" | "omit";

// A column of an entity grid: a regular MUI DataGrid column definition, optionally naming the
// server-side entity property that server-side sorting should order by when this column's own
// `field` is not a direct property of the entity (e.g. a column rendering a dereferenced
// `schemaVersion` object). Columns computed from data the server cannot order by should set
// `sortable: false` instead. The card* properties shape the narrow-screen card, which is
// otherwise derived from the column's regular definition.
export type EntityGridColumn = GridColDef<EntityRow> & {
  sortProperty?: string;
  cardSlot?: EntityGridCardSlot;
  // Overrides the column's grid rendering on the card, when the card wants a more compact
  // form (e.g. a day instead of a full timestamp, or a fallback for a missing title)
  cardValue?: (row: EntityRow) => ReactNode;
};

// How to present one entity type in a data grid: where its entities live, the columns to show,
// and the initial sort order. A module defining an entity type registers its configuration with
// registerEntityType, and any grid can then render that type by name.
export interface EntityGridConfig {
  // The homepage path under which the entities live, e.g. "/Submissions"
  homepage: string;
  columns: EntityGridColumn[];
  // The initial sort: the `field` of one of the columns, and a direction
  defaultSort?: { field: string; sort: GridSortDirection };
  // Where clicking a row navigates to (an in-app URL). Rows aren't clickable when this is
  // absent, and individual rows aren't when it returns undefined.
  rowLink?: (row: EntityRow) => string | undefined;
  // The escape hatch for a fully bespoke narrow-screen card, for compositions the columns'
  // cardSlots cannot describe. When absent — the normal case — the card is composed from the
  // visible columns according to their cardSlots. A bespoke renderer is expected to honor the
  // user's column selection itself: visibleFields holds the `field`s of the currently shown
  // columns, so the renderer can leave out what the user hid (keeping the card's identity —
  // the title — is fine regardless).
  listItem?: (row: EntityRow, visibleFields: ReadonlySet<string>) => ReactNode;
}

const configs = new Map<string, EntityGridConfig>();

// Registers (or replaces) the grid configuration for an entity type, e.g. "sub/Submission".
// Modules are expected to call this at import time, from the same file that consumers import
// the configuration from, so registration always happens before the first render.
export function registerEntityType(type: string, config: EntityGridConfig): void {
  configs.set(type, config);
}

// The grid configuration registered for an entity type, if any.
export function getEntityTypeConfig(type: string): EntityGridConfig | undefined {
  return configs.get(type);
}
