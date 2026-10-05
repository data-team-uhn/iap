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

import { useMemo } from "react";

import { Box } from "@mui/material";

import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";

import WorkflowVersionActions from "./WorkflowVersionActions";
import { versionOf, versionRow, WORKFLOW_VERSION_TYPE } from "./workflowVersionGrid";

import type { WorkflowSummary } from "./workflowModel";

// A workflow's versions, each with where it stands and what can be done with it. The workflow's page
// has already read them, with what the server offers on each, so the grid lists them as they are.
function WorkflowVersionList({ workflow, reload }: {
  workflow: WorkflowSummary;
  reload: () => void;
}) {
  const actionsColumn = useMemo<EntityGridColumn[]>(() => [ {
    field: "__actions__",
    headerName: "Actions",
    width: 220,
    cardSlot: "omit",
    renderCell: params => (
      // Kept from the row: a click on an action, or inside its dialog, is not a click on the version
      <Box onClick={event => event.stopPropagation()}>
        <WorkflowVersionActions version={versionOf(params.row)} workflow={workflow} reload={reload} />
      </Box>
    ),
  } ], [ workflow, reload ]);

  const versions = useMemo(() => workflow.versions.map(versionRow), [ workflow ]);

  return (
    <EntityDataGrid
      entityType={WORKFLOW_VERSION_TYPE}
      rows={versions}
      extraColumns={actionsColumn}
      pageSize={10}
      pageSizeOptions={[ 10, 25, 50 ]}
      emptyMessage="This workflow has no versions yet."
      noResultsMessage="No version matches"
      searchLabel="Search versions"
    />
  );
}

export default WorkflowVersionList;
