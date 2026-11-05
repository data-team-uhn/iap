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

import { FormControlLabel, Switch } from "@mui/material";

import AdminScreen from "@iap/admin-console/AdminScreen";
import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { PropertyFilter } from "@iap/frontend-commons/entityGrid/pagination";

// Imported to register the grid of err/LoggedError, which the grid below names by type
import "./errorGrid";
import { LOGGED_ERROR_TYPE, TRIAGE_PROPERTY, UNACKNOWLEDGED } from "./errorTrackingApi";

/**
 * The administration console page listing the recorded errors, at `/admin/errors`.
 *
 * It is the shared entity grid, which pages, sorts, searches and filters through
 * `/LoggedErrors.paginate.json`, with a switch that shows only the errors that need attention.
 */
function LoggedErrorsBrowser() {
  const [ outstandingOnly, setOutstandingOnly ] = useState(false);

  // Memoized, since a new array on every render restarts the grid's fetch
  const filters = useMemo<PropertyFilter[]>(
    () => (outstandingOnly ? [ { name: TRIAGE_PROPERTY, value: UNACKNOWLEDGED } ] : []),
    [ outstandingOnly ],
  );

  return (
    <AdminScreen
      title="Recorded errors"
      description={"Faults the instance could not deal with on its own. Each row is one distinct fault, however "
        + "many times it has happened; open one to see the details and record what you decided."}
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
