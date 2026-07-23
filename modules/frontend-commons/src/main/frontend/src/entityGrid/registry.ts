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

import type { GridColDef, GridSortDirection } from "@mui/x-data-grid-pro";

import type { EntityRow } from "./pagination";

// A column of an entity grid: a regular MUI DataGrid column definition, optionally naming the
// server-side entity property that server-side sorting should order by when this column's own
// `field` is not a direct property of the entity (e.g. a column rendering a dereferenced
// `schemaVersion` object). Columns computed from data the server cannot order by should set
// `sortable: false` instead.
export type EntityGridColumn = GridColDef<EntityRow> & {
  sortProperty?: string;
};

// How to present one entity type in a data grid: where its entities live, the columns to show,
// and the initial sort order. A module defining an entity type registers its configuration with
// registerEntityType, and any grid can then render that type by name.
export type EntityGridConfig = {
  // The homepage path under which the entities live, e.g. "/Submissions"
  homepage: string;
  columns: EntityGridColumn[];
  // The initial sort: the `field` of one of the columns, and a direction
  defaultSort?: { field: string; sort: GridSortDirection };
  // Where clicking a row navigates to (an in-app URL). Rows aren't clickable when this is
  // absent, and individual rows aren't when it returns undefined.
  rowLink?: (row: EntityRow) => string | undefined;
};

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
