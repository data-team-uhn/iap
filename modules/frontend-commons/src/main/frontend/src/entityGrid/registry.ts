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

// Where a column's content goes on the card that stands for a row on a narrow screen:
// - "title" leads the card.
// - "badge" sits beside the title, such as a status chip.
// - "actions" ends the title's line. It holds what can be done with the entity as one compact
//   control, such as a menu, because a click on the card itself opens the entity.
// - "caption" goes on the muted line under the title. Several captions join into one " • " line.
// - "row" is a labeled row, and the default.
// - "omit" leaves the column off the card.
export type EntityGridCardSlot = "title" | "badge" | "caption" | "row" | "actions" | "omit";

// A column of an entity grid: an MUI DataGrid column definition, and where it goes on the
// narrow-screen card. A column computed from data the server cannot order by sets
// `sortable: false`.
export type EntityGridColumn = GridColDef<EntityRow> & {
  // The entity property the server sorts and filters by, when `field` is not one, such as a
  // column showing a dereferenced `schemaVersion`; `field` if absent
  sortProperty?: string;
  // "row" if absent
  cardSlot?: EntityGridCardSlot;
  // What the card shows for the column, such as a date instead of a full timestamp; the column's
  // grid rendering if absent
  cardValue?: (row: EntityRow) => ReactNode;
};

// The server-side property a column stands for, which sorting orders by and filtering compares
export function serverProperty(column: EntityGridColumn): string {
  return column.sortProperty ?? column.field;
}

// Rows nested under each listed entity, shown beneath it as a tree, such as the versions of a
// schema. They come inside their entity's JSON, so paging, sorting and searching apply to the
// entities alone.
export interface EntityGridChildren {
  // Selectors for each entity's JSON that include its children, such as "1" for one level down
  selectors: string;
  // One entity's children, read out of its JSON as rows of their own
  rows: (row: EntityRow) => EntityRow[];
  // The field of the column that names each row in the tree, entity or child, such as "title". The
  // tree column, with the expand toggle, takes that column's place.
  treeField: string;
  // Whether entities start expanded, showing their children; collapsed if absent
  expanded?: boolean;
  // How the number of an entity's children is worded beside its name, such as "2 versions"; the bare
  // number if absent
  countLabel?: (count: number) => string;
}

// How one entity type shows in a data grid: where its entities live, the columns to show, and the
// initial sort. A module defining an entity type registers it with registerEntityType, and any grid
// can then list that type by name.
export interface EntityGridConfig {
  // The path of the homepage the entities live under, such as "/Submissions"
  homepage: string;
  columns: EntityGridColumn[];
  // The initial sort: the `field` of one of the columns, and a direction; the server's own order if absent
  defaultSort?: { field: string; sort: GridSortDirection };
  // The in-app URL a click on a row opens. No row can be clicked if this is absent, and a row it
  // returns undefined for cannot.
  rowLink?: (row: EntityRow) => string | undefined;
  // Draws the narrow-screen card itself, for a card the columns' slots cannot describe. If absent,
  // the card is composed from the visible columns by their slots. `visibleFields` holds the
  // `field`s of the columns the user shows, and the card is expected to leave out the others. It
  // may keep its title regardless.
  listItem?: (row: EntityRow, visibleFields: ReadonlySet<string>) => ReactNode;
  // Rows to show nested under each entity. On a narrow screen an entity keeps one card. The
  // composed card ends with the children, each named by its tree field, and linked where rowLink
  // gives it a page. A listItem shows them itself.
  children?: EntityGridChildren;
}

const configs = new Map<string, EntityGridConfig>();

// Registers the grid configuration for an entity type, such as "sub/Submission", replacing any
// earlier one. Call it at import time, in a file that every grid of the type imports, so that it
// runs before the first render.
export function registerEntityType(type: string, config: EntityGridConfig): void {
  configs.set(type, config);
}

// The grid configuration registered for an entity type; undefined if there is none.
export function getEntityTypeConfig(type: string): EntityGridConfig | undefined {
  return configs.get(type);
}
