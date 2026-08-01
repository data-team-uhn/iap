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

import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { PropertyFilter } from "@iap/frontend-commons/entityGrid/pagination";

import { SUBMISSION_TYPE } from "./submissionGrid";

// Only the submissions created by the current user; `@me` is resolved server-side.
const MY_SUBMISSIONS: PropertyFilter[] = [{ name: "jcr:createdBy", value: "@me" }];

// The dashboard widget listing the current user's own submissions, newest activity first,
// registered on the `iap/dashboard/widget` extension point. The surrounding titled frame is
// provided by the dashboard, so this only renders the grid.
function MySubmissionsWidget() {
  return (
    <EntityDataGrid
      entityType={SUBMISSION_TYPE}
      filters={MY_SUBMISSIONS}
      emptyMessage="No submissions"
      noResultsMessage="No matching submissions"
    />
  );
}

export default MySubmissionsWidget;
