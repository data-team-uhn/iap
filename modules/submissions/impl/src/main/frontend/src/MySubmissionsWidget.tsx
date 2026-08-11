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

import { useState } from "react";

import AddIcon from "@mui/icons-material/Add";
import { Box, Button, Stack } from "@mui/material";
import { useNavigate } from "react-router";

import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { PropertyFilter } from "@iap/frontend-commons/entityGrid/pagination";

import NewSubmissionDialog from "./NewSubmissionDialog";
import { SUBMISSION_TYPE } from "./submissionGrid";

// Only the submissions created by the current user; `@me` is resolved server-side. Deliberately
// `createdBy` and not `jcr:createdBy`: submissions are written by the workflow engine's own service
// user, so the JCR property names the engine, and the person it acted for is recorded separately.
const MY_SUBMISSIONS: PropertyFilter[] = [{ name: "createdBy", value: "@me" }];

// The dashboard widget listing the current user's own submissions, newest activity first,
// registered on the `iap/dashboard/widget` extension point. The surrounding titled frame is
// provided by the dashboard, so this adds only the action for raising a new one.
//
// The action sits above the grid rather than in its toolbar: that toolbar is the grid's own
// vocabulary — search, columns, filters, all about what is being *looked at* — while creating
// something is about the collection, and it belongs where nothing has to be found first.
function MySubmissionsWidget() {
  const [ dialogOpen, setDialogOpen ] = useState(false);
  const navigate = useNavigate();

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
      <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setDialogOpen(true)}>
          New submission
        </Button>
      </Box>
      <EntityDataGrid
        entityType={SUBMISSION_TYPE}
        filters={MY_SUBMISSIONS}
        emptyMessage="No submissions"
        noResultsMessage="No matching submissions"
      />
      { /* Mounted only while open, so each opening reads what is on offer afresh and never
           reopens onto a half-filled attempt */ }
      { dialogOpen && <NewSubmissionDialog onClose={() => setDialogOpen(false)} onCreated={created} /> }
    </Stack>
  );
}

export default MySubmissionsWidget;
