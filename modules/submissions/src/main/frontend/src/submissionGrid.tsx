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

import StatusChip from "./StatusChip";

// The entity type listed by submission grids, as registered with the entity grid registry.
export const SUBMISSION_TYPE = "sub/Submission";

// Renders the "Schema" cell: the serialized submission carries its dereferenced `schemaVersion`
// node, whose own properties hold the version label, while the owning schema's name is the
// second-to-last segment of its path (/Schemas/<schema>/<version>).
// TODO: display the schema's title instead of its node name, once the serialization can embed it.
export function schemaLabel(schemaVersion: unknown): string {
  if (!schemaVersion || typeof schemaVersion !== "object") {
    return "";
  }
  const version = schemaVersion as Record<string, unknown>;
  const pathSegments = String(version["@path"] ?? "").split("/");
  const schemaName = pathSegments.length > 2 ? pathSegments[pathSegments.length - 2] : "";
  return [schemaName, version.version].filter(Boolean).join(" ");
}

function dateValue(value: unknown): Date | null {
  return value ? new Date(String(value)) : null;
}

// The columns shared by all grids listing submissions. Fields named after an entity property are
// sorted server-side by that property; the schema column is computed from a referenced node, so
// it is not sortable.
const SUBMISSION_COLUMNS: EntityGridColumn[] = [
  { field: "title", headerName: "Title", flex: 2, minWidth: 160 },
  {
    field: "schemaVersion",
    headerName: "Schema",
    flex: 1,
    minWidth: 120,
    // Computed from the referenced schema version node, which the server can neither order by
    // nor filter on
    sortable: false,
    filterable: false,
    valueGetter: value => schemaLabel(value),
  },
  {
    field: "status",
    headerName: "Status",
    width: 130,
    type: "singleSelect",
    valueOptions: ["draft", "submitted", "in-review", "approved", "rejected"],
    renderCell: params => <StatusChip value={params.value} />,
  },
  {
    field: "jcr:created",
    headerName: "Created",
    width: 160,
    type: "dateTime",
    valueGetter: value => dateValue(value),
  },
  {
    field: "jcr:lastModified",
    headerName: "Last modified",
    width: 160,
    type: "dateTime",
    valueGetter: value => dateValue(value),
  },
];

// Registering at import time means any component importing anything from this file can render an
// EntityDataGrid for submissions.
registerEntityType(SUBMISSION_TYPE, {
  homepage: "/Submissions",
  columns: SUBMISSION_COLUMNS,
  defaultSort: { field: "jcr:lastModified", sort: "desc" },
  // Each submission is viewable on its own page, at its repository path
  rowLink: row => row["@path"] ? String(row["@path"]) : undefined,
});
