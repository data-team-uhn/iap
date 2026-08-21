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

// Only the submissions created by the current user; `@me` is resolved server-side.
//
// `createdBy` and not `jcr:createdBy`: every submission is raised through the workflow engine, which
// writes as its own service user, so the JCR property names the engine and the person it acted for is
// recorded separately. And `jcr:createdBy` is not worth ORing in as a fallback either, which is the
// tempting mistake: it could only match content a user's own session wrote directly, which the engine
// exists to prevent, while seeded content carries `sling-jcr-content-loader` there and matches nobody.
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

// The dashboard widget listing the current user's own submissions, newest activity first,
// registered on the `iap/dashboard/widget` extension point.
//
// It draws its own header — the extension asks the dashboard for `iap:widgetHideHeader` — so that
// the action for raising a submission sits on the title's line, where it reads as something the
// widget offers, rather than floating in a band of its own above the table. Title and subtitle
// still come from the extension, so they are declared in exactly one place.
function MySubmissionsWidget({ extension }: MySubmissionsWidgetProps) {
  const [ dialogOpen, setDialogOpen ] = useState(false);
  // Bumped when a row is deleted, which is a change to what the listing should say that the grid
  // has no way of noticing on its own
  const [ refreshToken, setRefreshToken ] = useState(0);
  const navigate = useNavigate();

  const columns: EntityGridColumn[] = useMemo(() => [ {
    field: "__actions__",
    headerName: "Actions",
    width: 130,
    // Not a property of the entity, so there is nothing for the server to sort or filter on
    sortable: false,
    filterable: false,
    // The narrow-screen card is a tap target that opens the submission; a row of controls inside
    // one would compete with it
    cardSlot: "omit",
    renderCell: params => (
      <SubmissionActions
        path={params.row["@path"] as string | undefined}
        title={params.row.title as string | undefined}
        onDeleted={() => setRefreshToken(current => current + 1)}
      />
    ),
  } ], []);

  // The grid reads the server on mount, so opening what was just created shows it, and coming
  // back lists it: no refresh of our own, and nothing stale left on screen either way. A
  // submission raised without a redirect to follow leaves the dashboard as it is.
  const created = (path: string) => {
    setDialogOpen(false);
    if (path) {
      void navigate(path);
    }
  };

  return (
    <Stack spacing={1}>
      {/* The title and the action share a line, with the subtitle under the title where the
          dashboard's own header would have put it */}
      <Box sx={{ display: "flex", alignItems: "flex-start", gap: 2 }}>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          {/* Only when there is one: an empty heading is markup a screen reader still announces */}
          { text(extension, "iap:extensionName") && (
            <Typography variant="h6">{text(extension, "iap:extensionName")}</Typography>
          ) }
          { text(extension, "iap:subtitle") && (
            <Typography variant="body2" color="text.secondary">{text(extension, "iap:subtitle")}</Typography>
          ) }
        </Box>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setDialogOpen(true)}>
          New submission
        </Button>
      </Box>
      <EntityDataGrid
        entityType={SUBMISSION_TYPE}
        filters={MY_SUBMISSIONS}
        extraColumns={columns}
        refreshToken={refreshToken}
        emptyMessage="No submissions"
        noResultsMessage="No matching submissions"
        searchLabel="Search my submissions"
      />
      { /* Mounted only while open, so each opening reads what is on offer afresh and never
           reopens onto a half-filled attempt */ }
      { dialogOpen && <NewSubmissionDialog onClose={() => setDialogOpen(false)} onCreated={created} /> }
    </Stack>
  );
}

export default MySubmissionsWidget;
