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
import { Box, Button, Stack, Typography } from "@mui/material";
import { useNavigate } from "react-router";

import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { PropertyFilter } from "@iap/frontend-commons/entityGrid/pagination";
import type { EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";

import NewSubmissionDialog from "./NewSubmissionDialog";
import SubmissionActions from "./SubmissionActions";
import { SUBMISSION_TYPE } from "./submissionGrid";

// The submissions the current user raised. The server resolves `@me`. `createdBy` is the person the
// engine acted for, while `jcr:createdBy` names the engine's service user.
const MY_SUBMISSIONS: PropertyFilter[] = [{ name: "createdBy", value: "@me" }];

// One dashboard widget extension, as the dashboard hands it to the widget it renders.
type WidgetExtension = Record<string, unknown>;

function text(extension: WidgetExtension | undefined, key: string): string | undefined {
  const value = extension?.[key];
  return typeof value === "string" ? value : undefined;
}

interface MySubmissionsWidgetProps {
  extension?: WidgetExtension;
}

// The dashboard widget listing the submissions the current user raised, the most recently modified
// first. It draws its own header, with the action for raising a submission on the title's line. The
// title and subtitle come from the extension, which sets `ext:widgetHideHeader`.
function MySubmissionsWidget({ extension }: MySubmissionsWidgetProps) {
  const [ dialogOpen, setDialogOpen ] = useState(false);
  // Bumped to have the grid read the listing again, after a deletion or a creation it cannot see
  const [ refreshToken, setRefreshToken ] = useState(0);
  const navigate = useNavigate();

  const extraColumns: EntityGridColumn[] = useMemo(() => [ {
    field: "__actions__",
    headerName: "Actions",
    width: 130,
    // Not a property of the entity, so there is nothing for the server to sort or filter on
    sortable: false,
    filterable: false,
    // The narrow-screen card opens the submission when tapped, so it carries no controls
    cardSlot: "omit",
    renderCell: params => (
      <SubmissionActions
        path={params.row["@path"] as string | undefined}
        title={params.row.title as string | undefined}
        onDeleted={() => setRefreshToken(current => current + 1)}
      />
    ),
  } ], []);

  // The grid reads the listing again whether or not there is a submission to open
  const created = (path: string) => {
    setDialogOpen(false);
    setRefreshToken(current => current + 1);
    if (path) {
      void navigate(path);
    }
  };

  return (
    <Stack spacing={1}>
      {/* The title and the action share a line, with the subtitle under the title */}
      <Box sx={{ display: "flex", alignItems: "flex-start", gap: 2 }}>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          {/* Only when there is one, since a screen reader announces an empty heading */}
          { text(extension, "ext:name") && (
            <Typography variant="h6">{text(extension, "ext:name")}</Typography>
          ) }
          { text(extension, "ext:subtitle") && (
            <Typography variant="description">{text(extension, "ext:subtitle")}</Typography>
          ) }
        </Box>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setDialogOpen(true)}>
          New submission
        </Button>
      </Box>
      <EntityDataGrid
        entityType={SUBMISSION_TYPE}
        filters={MY_SUBMISSIONS}
        extraColumns={extraColumns}
        refreshToken={refreshToken}
        emptyMessage="No submissions"
        noResultsMessage="No matching submissions"
      />
      { /* Mounted only while open, so that each opening reads the schemas on offer again and starts
           empty */ }
      { dialogOpen && <NewSubmissionDialog onClose={() => setDialogOpen(false)} onCreated={created} /> }
    </Stack>
  );
}

export default MySubmissionsWidget;
