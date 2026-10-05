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

import { Stack, Typography } from "@mui/material";

import type { EntityRow } from "@iap/frontend-commons/entityGrid/pagination";
import { type EntityGridColumn, registerEntityType } from "@iap/frontend-commons/entityGrid/registry";

import { adminUrl, WORKFLOWS_ROOT, type WorkflowVersionSummary } from "./workflowModel";
import WorkflowStateChip from "./WorkflowStateChip";

export const WORKFLOW_VERSION_TYPE = "wf/WorkflowVersion";

// A version as a grid row: its summary, with the path a grid tells its rows apart by
export const versionRow = (version: WorkflowVersionSummary): EntityRow => ({ ...version, "@path": version.path });

// The version a grid row is
export const versionOf = (row: EntityRow): WorkflowVersionSummary => row as unknown as WorkflowVersionSummary;

const labelOf = (version: WorkflowVersionSummary): string => version.version || version.name;

// The versions of one workflow, as its page lists them: rows it already has, sorted in the browser, in
// the repository's order until asked otherwise. By label, numbers compare as numbers (2.0 before 10.0).
const COLUMNS: EntityGridColumn[] = [
  {
    field: "version",
    headerName: "Version",
    width: 120,
    valueGetter: (_value, row) => labelOf(versionOf(row)),
    sortComparator: (one: string, other: string) => one.localeCompare(other, undefined, { numeric: true }),
  },
  {
    field: "state",
    headerName: "State",
    width: 130,
    sortable: false,
    filterable: false,
    renderCell: params => <WorkflowStateChip state={versionOf(params.row).state} />,
  },
  { field: "description", headerName: "Description", flex: 2, minWidth: 180 },
  {
    field: "lastModified",
    headerName: "Last modified",
    width: 170,
    type: "dateTime",
    valueGetter: value => typeof value === "string" && value !== "" ? new Date(value) : null,
  },
];

// A version on a phone: its label and state, and its description
function VersionCard({ version }: { version: WorkflowVersionSummary }) {
  return (
    <Stack spacing={0.5} sx={{ py: 1 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Typography>{`Version ${labelOf(version)}`}</Typography>
        <WorkflowStateChip state={version.state} />
      </Stack>
      { version.description && <Typography variant="description">{version.description}</Typography> }
    </Stack>
  );
}

// Registered at import, so any grid importing this module can list workflow versions. A row opens the
// version it is.
registerEntityType(WORKFLOW_VERSION_TYPE, {
  homepage: WORKFLOWS_ROOT,
  columns: COLUMNS,
  rowLink: row => adminUrl(versionOf(row).path),
  listItem: row => <VersionCard version={versionOf(row)} />,
});
