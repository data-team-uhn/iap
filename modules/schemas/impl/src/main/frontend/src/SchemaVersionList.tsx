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

import { type JcrNode, versionsOf } from "./schemaModel";
import SchemaVersionActions from "./SchemaVersionActions";
import { SCHEMA_VERSION_TYPE } from "./schemaVersionGrid";

// A schema's versions, each with where it stands and what can be done with it. The schema's page has
// already read them, with what the server offers on each, so the grid lists them as they are.
function SchemaVersionList({ schema, reload, comparisonDefaults }: {
  schema: JcrNode;
  reload: () => void;
  // The rules choosing what a version is compared with by default
  comparisonDefaults: string[];
}) {
  const actionsColumn = useMemo<EntityGridColumn[]>(() => [ {
    field: "__actions__",
    headerName: "Actions",
    width: 200,
    cardSlot: "omit",
    renderCell: params => (
      // Kept from the row: a click on an action, or inside its dialog, is not a click on the version
      <Box onClick={event => event.stopPropagation()}>
        <SchemaVersionActions version={params.row} schema={schema} reload={reload}
          comparisonDefaults={comparisonDefaults} />
      </Box>
    ),
  } ], [ schema, reload, comparisonDefaults ]);

  const versions = useMemo(() => versionsOf(schema), [ schema ]);

  return (
    <EntityDataGrid
      entityType={SCHEMA_VERSION_TYPE}
      rows={versions}
      extraColumns={actionsColumn}
      pageSize={10}
      pageSizeOptions={[ 10, 25, 50 ]}
      emptyMessage="This schema has no versions."
      noResultsMessage="No version matches"
      searchLabel="Search versions"
    />
  );
}

export default SchemaVersionList;
