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

import { type EntityGridColumn, registerEntityType } from "@iap/frontend-commons/entityGrid/registry";

import { WORKFLOWS_ROOT, adminUrl } from "./workflowModel";

// The entity type listed by workflow grids, as registered with the entity grid registry.
export const WORKFLOW_TYPE = "wf/WorkflowDefinition";

function dateValue(value: unknown): Date | null {
  return typeof value === "string" || typeof value === "number" ? new Date(value) : null;
}

// The columns of a workflow listing. Deliberately no "enabled" column: whether a workflow runs is
// whether one of its versions is active, and a listing serializes the definition nodes only — the
// versions are not in the page it is built from. The workflow's own page, which loads them, is where
// that question is answered.
const WORKFLOW_COLUMNS: EntityGridColumn[] = [
  {
    field: "title",
    headerName: "Workflow",
    flex: 2,
    minWidth: 160,
    cardSlot: "title",
    // Unlike a grid cell, the card cannot leave an untitled workflow blank: the title is the card's
    // identity and its tap target, so it falls back to the node name
    cardValue: row => (row.title as string | undefined) ?? (row["@name"] as string | undefined),
  },
  {
    field: "jcr:created",
    headerName: "Created",
    width: 160,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    // The modification day already dates the card; a second timestamp would just crowd it
    cardSlot: "omit",
  },
  {
    field: "jcr:lastModified",
    headerName: "Last modified",
    width: 160,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    cardSlot: "caption",
    // The full timestamp shown in the grid is too long for the caption line; the day is enough
    cardValue: row => dateValue(row["jcr:lastModified"])?.toLocaleDateString(),
  },
];

// Registering at import time means any component importing anything from this file can render an
// EntityDataGrid for workflows.
//
// The registered homepage is the one every deployment has. A listing that has discovered others
// passes them to the grid, which then addresses them all; nothing here needs to know they exist.
registerEntityType(WORKFLOW_TYPE, {
  homepage: WORKFLOWS_ROOT,
  columns: WORKFLOW_COLUMNS,
  defaultSort: { field: "title", sort: "asc" },
  // Clicking a workflow opens the page managing it and its versions
  rowLink: row => {
    const path = row["@path"];
    return typeof path === "string" ? adminUrl(path) : undefined;
  },
});
