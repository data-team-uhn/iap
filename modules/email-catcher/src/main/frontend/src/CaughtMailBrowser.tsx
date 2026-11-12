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

import { Alert, Chip } from "@mui/material";

import AdminScreen from "@iap/admin-console/AdminScreen";
import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";

import { CAUGHT_MESSAGE_TYPE } from "./caughtMailModel";
import { useCatcherStatus } from "./useCaughtMail";
// Imported for its side effect: it registers the grid columns of mail/CaughtMessage
import "./caughtMailGrid";

/**
 * The administration console page that lists the caught messages, at `/admin/mail`. It also says
 * whether the catcher is on, since an empty list means nothing was sent only while it is.
 */
function CaughtMailBrowser() {
  // A failure to read the status is not shown: the grid reports its own failure to read /CaughtMail
  const { status } = useCatcherStatus();

  return (
    <AdminScreen
      title="Caught mail"
      description={"What this instance would have emailed, filed here instead of being delivered. Open a "
        + "message to read it as a recipient would have."}
      action={status !== null && (
        <Chip
          size="small"
          label={status.enabled ? "Catching mail" : "Not catching"}
          color={status.enabled ? "success" : "default"}
        />
      )}
    >
      {status?.enabled === false && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Mail is being delivered normally, so nothing new will appear here. Switch the catcher on
          from the administration dashboard, or under <em>IAP Email Catcher</em> in the OSGi
          configuration console.
        </Alert>
      )}
      <EntityDataGrid
        entityType={CAUGHT_MESSAGE_TYPE}
        searchLabel="Search the caught mail"
        emptyMessage="Nothing has been caught yet."
        noResultsMessage="No caught message matches this search."
      />
    </AdminScreen>
  );
}

export default CaughtMailBrowser;
