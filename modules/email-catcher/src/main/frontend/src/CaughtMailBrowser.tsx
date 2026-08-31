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

import { useEffect, useState } from "react";

import { Alert, Chip, Typography } from "@mui/material";

import AdminScreen from "@iap/admin-console/AdminScreen";
import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { CAUGHT_MESSAGE_TYPE, type CatcherStatus, fetchCatcherStatus } from "./caughtMailApi";
// Imported for its side effect: registers the presentation of mail/CaughtMessage before the first
// render, which is what lets the grid above be asked for by type name alone
import "./caughtMailGrid";

/**
 * The administration console page listing what the catcher has filed, at {@code /admin/mail}.
 *
 * Whether the catcher is on is stated here rather than left to be inferred from the list, because an
 * empty list means opposite things either way round: with the catcher on it means nothing has been
 * sent, with it off it means everything sent went out by mail and this page will never show it.
 */
function CaughtMailBrowser() {
  const doFetch = useAuthenticatedFetch();
  const [ status, setStatus ] = useState<CatcherStatus | null>(null);

  useEffect(() => {
    let cancelled = false;
    void fetchCatcherStatus(doFetch)
      // The list below reports its own failures, and it is the page's substance; a second report of
      // the same unreadable folder would say nothing more
      .catch(() => null)
      .then(result => { if (!cancelled) { setStatus(result); } });
    return () => { cancelled = true; };
  }, [ doFetch ]);

  return (
    <AdminScreen
      title="Caught mail"
      action={status !== null && (
        <Chip
          size="small"
          label={status.enabled ? "Catching mail" : "Not catching"}
          color={status.enabled ? "success" : "default"}
        />
      )}
    >
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        What this instance would have emailed, filed here instead of being delivered. Open a message
        to read it as a recipient would have.
      </Typography>
      {status?.enabled === false && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Mail is being delivered normally, so nothing new will appear here. Switch the catcher on
          under <em>IAP Email Catcher</em> in the OSGi configuration console.
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
