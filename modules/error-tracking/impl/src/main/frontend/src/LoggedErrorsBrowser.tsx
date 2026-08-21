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

import { FormControlLabel, Switch, Typography } from "@mui/material";

import AdminScreen from "@iap/admin-console/AdminScreen";
import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { PropertyFilter } from "@iap/frontend-commons/entityGrid/pagination";

// Imported for its side effect: registers the presentation of err/LoggedError before the first
// render, which is what lets the grid below be asked for by type name alone
import "./errorGrid";
import { LOGGED_ERROR_TYPE, TRIAGE_PROPERTY, UNACKNOWLEDGED } from "./errorTrackingApi";

/**
 * The administration console page listing the recorded errors, at {@code /admin/errors}.
 *
 * Almost all of it is the shared entity grid, which the errors' homepage already answers for: it is
 * an iap:EntityHomepage, so listing, filtering, sorting and paging come from the pagination servlet
 * with no server-side code of this feature's own.
 *
 * The one thing added on top is the "only what needs attention" switch. The grid's own column filter
 * can express the same condition, but finding it takes several clicks, and this is the question
 * somebody opening this page is nearly always asking.
 */
function LoggedErrorsBrowser() {
  const [ outstandingOnly, setOutstandingOnly ] = useState(false);

  // A new array identity on every render would restart the grid's fetch effect on every keystroke
  // elsewhere on the page, so the filter list is memoized on the one thing it depends on
  const filters = useMemo<PropertyFilter[]>(
    () => (outstandingOnly ? [ { name: TRIAGE_PROPERTY, value: UNACKNOWLEDGED } ] : []),
    [ outstandingOnly ],
  );

  return (
    <AdminScreen
      title="Recorded errors"
      action={
        <FormControlLabel
          control={
            <Switch
              checked={outstandingOnly}
              onChange={event => { setOutstandingOnly(event.target.checked); }}
            />
          }
          label="Only what needs attention"
        />
      }
    >
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Faults the instance could not deal with on its own. Each row is one distinct fault, however
        many times it has happened; open one to see the details and record what you decided.
      </Typography>
      <EntityDataGrid
        entityType={LOGGED_ERROR_TYPE}
        filters={filters}
        emptyMessage={
          outstandingOnly
            ? "Nothing needs attention."
            : "Nothing has been recorded yet."
        }
        noResultsMessage="No recorded error matches this search."
      />
    </AdminScreen>
  );
}

export default LoggedErrorsBrowser;
