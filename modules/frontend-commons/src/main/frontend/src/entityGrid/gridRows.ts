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

import { GRID_TREE_DATA_GROUPING_FIELD, type GridGroupingColDefOverride } from "@mui/x-data-grid-pro";

import type { EntityRow } from "./pagination";
import type { EntityGridChildren, EntityGridColumn } from "./registry";

// Where a row sits in the tree, as the grid's getTreeDataPath reads it
const TREE_PATH = "__treePath__";

// A row's key in the tree: its path, or its name when a projection left the path out
const rowPath = (row: EntityRow): string => String(row["@path"] ?? row["@name"]);

// The rows as the grid shows them: in a tree, each entity followed by its children, each carrying
// its place in the tree. Keyed by path, which is unique at every level; the tree column shows the
// row's name instead.
export function treeRows(rows: EntityRow[], tree?: EntityGridChildren): EntityRow[] {
  return tree
    ? rows.flatMap(row => {
      const parent = rowPath(row);
      return [ { ...row, [TREE_PATH]: [ parent ] },
        ...tree.rows(row).map(child => ({ ...child, [TREE_PATH]: [ parent, rowPath(child) ] })) ];
    })
    : rows;
}

export const treeDataPath = (row: EntityRow): string[] => row[TREE_PATH] as string[];

// What a row is called in the tree: its tree field, or its name when it has none
export const treeName = (row: EntityRow, tree: EntityGridChildren): unknown => row[tree.treeField] ?? row["@name"];

// A row is identified by where it lives. `@name` is only unique among siblings, but the rows of
// one grid are siblings, so it stands in when a projection omits the path; falling through to
// the row's position keeps two such rows apart, which a shared "undefined" would not -- the
// grid throws on duplicate ids and would take the whole widget down with it.
export function rowId(row: EntityRow, rows: EntityRow[]): string {
  const path = row["@path"] ?? row["@name"];
  return typeof path === "string" ? path : `@${rows.indexOf(row)}`;
}

// In a tree the sort on the named column is shown on the tree column that took its place, and
// sent to the server as the column it stands for
export const toTreeField = (field: string, tree?: EntityGridChildren): string =>
  field === tree?.treeField ? GRID_TREE_DATA_GROUPING_FIELD : field;

export const fromTreeField = (field: string, tree?: EntityGridChildren): string =>
  tree && field === GRID_TREE_DATA_GROUPING_FIELD ? tree.treeField : field;

// The class of an entity's own cell in the tree column, which stands out from the rows nested under it
export const ENTITY_CELL = "entity-grid-entity";

// The tree column, presented as the column whose place it takes
export function groupingColumn(columns: EntityGridColumn[], tree: EntityGridChildren)
  : GridGroupingColDefOverride<EntityRow> {
  const treeColumn = columns.find(column => column.field === tree.treeField);
  return {
    headerName: treeColumn?.headerName,
    flex: treeColumn?.flex ?? 1,
    minWidth: treeColumn?.minWidth,
    sortable: treeColumn?.sortable !== false,
    filterable: false,
    valueGetter: (_value: never, row: EntityRow) => treeName(row, tree),
    cellClassName: params => (treeDataPath(params.row).length === 1 ? ENTITY_CELL : ""),
  };
}
