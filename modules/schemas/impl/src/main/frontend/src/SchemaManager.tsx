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

import { useMemo, useState } from "react";

import AddIcon from "@mui/icons-material/Add";
import { Box, Button } from "@mui/material";
import { useNavigate } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import NoticeSnackbar, { type Notice } from "@iap/frontend-commons/components/NoticeSnackbar";
import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { sendEvent } from "@iap/frontend-commons/workflowEvents";

import NewSchemaDialog from "./NewSchemaDialog";
import SchemaActions from "./SchemaActions";
import { SCHEMA_OF, SCHEMA_TYPE } from "./schemaGrid";
import { schemaNameFromRoute, SCHEMAS_ROOT, type JcrNode } from "./schemaModel";
import SchemaVersionActions from "./SchemaVersionActions";
import { schemaPageUrl } from "./useSchemaList";

// The "Schemas" administrative tool: every schema, searchable and sortable, with its versions nested
// under it and the actions offered on each.
function SchemaManager() {
  const [ creating, setCreating ] = useState(false);
  const [ refreshToken, setRefreshToken ] = useState(0);
  const [ notice, setNotice ] = useState<Notice>();
  const navigate = useNavigate();
  const doFetch = useAuthenticatedFetch();

  const actionsColumn = useMemo<EntityGridColumn[]>(() => [ {
    field: "__actions__",
    headerName: "Actions",
    width: 280,
    cardSlot: "omit",
    renderCell: params => {
      const row = params.row;
      const parent = row[SCHEMA_OF] as JcrNode | undefined;
      const schema = parent ?? row;
      const version = parent && row;
      const shared = {
        schema,
        reload: () => setRefreshToken(token => token + 1),
        report: (title: string) => setNotice({ title, severity: "success" }),
      };
      // Kept from the row, whose click opens the schema: a click on an action, or inside its dialog,
      // is not a request to navigate
      return (
        <Box onClick={event => event.stopPropagation()}>
          { version ? <SchemaVersionActions version={version} {...shared} /> : <SchemaActions {...shared} /> }
        </Box>
      );
    },
  } ], []);

  return (
    <AdminScreen
      title="Schemas"
      description={"What submissions are asked for. Each schema is versioned. A draft can change in any way, "
        + "an active version only in its wording."}
      action={
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)}>
          New schema
        </Button>
      }
    >
      <EntityDataGrid
        entityType={SCHEMA_TYPE}
        extraColumns={actionsColumn}
        refreshToken={refreshToken}
        pageSize={25}
        pageSizeOptions={[ 10, 25, 50 ]}
        height={640}
        emptyMessage={"No schemas are defined yet. Use \"New schema\" to create the first one."}
        noResultsMessage="No schema matches"
        searchLabel="Search schemas"
      />
      { creating && (
        <NewSchemaDialog
          onClose={() => setCreating(false)}
          onCreate={(title, version) => sendEvent(doFetch, SCHEMAS_ROOT, "create", { title, version })}
          onCreated={path => void navigate(schemaPageUrl(schemaNameFromRoute(path)))}
        />
      ) }
      <NoticeSnackbar notice={notice} onClose={() => setNotice(undefined)} />
    </AdminScreen>
  );
}

export default SchemaManager;
