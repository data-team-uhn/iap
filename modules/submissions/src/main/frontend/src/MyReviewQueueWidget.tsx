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
import type { DescendantFilter } from "@iap/frontend-commons/entityGrid/pagination";

import { SUBMISSION_TYPE } from "./submissionGrid";

// Only the submissions with an open review assigned to the current user: a sub:Review descendant
// whose reviewer is `@me` (resolved server-side) and whose state isn't final yet.
const MY_OPEN_REVIEWS: DescendantFilter = {
  type: "sub:Review",
  filters: [
    { name: "reviewer", value: "@me" },
    { name: "status", value: "approved", comparator: "<>" },
    { name: "status", value: "rejected", comparator: "<>" },
  ],
};

// The dashboard widget listing the submissions waiting for the current user's review, registered
// on the `iap/dashboard/widget` extension point. The surrounding titled frame is provided by the
// dashboard, so this only renders the grid.
function MyReviewQueueWidget() {
  return (
    <EntityDataGrid
      entityType={SUBMISSION_TYPE}
      childFilter={MY_OPEN_REVIEWS}
      emptyMessage="No submissions to review"
      noResultsMessage="No matching submissions"
    />
  );
}

export default MyReviewQueueWidget;
