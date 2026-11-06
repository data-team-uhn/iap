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

// The submissions with a review assigned to the current user and not yet decided: a `sub:Review`
// descendant whose reviewer is `@me`, and whose tags hold neither decision. On the multi-valued `tags`
// property, the servlet reads `<>` as "none of the values is".
const MY_OPEN_REVIEWS: DescendantFilter = {
  type: "sub:Review",
  filters: [
    { name: "reviewer", value: "@me" },
    { name: "tags", value: "approved", comparator: "<>" },
    { name: "tags", value: "rejected", comparator: "<>" },
  ],
};

// The dashboard widget listing the submissions waiting for the current user's review. The dashboard
// draws its title.
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
