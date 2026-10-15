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

// Queried by node type, which the grid's descendant filter takes, rather than by resource type
const TASK_INSTANCE_NODE_TYPE = "wf:TaskInstance";

// Only the submissions with an open task naming one of the principals the reader acts as, ported from
// IAP-88. `@myPrincipals` is expanded server-side into everything the session acts as, groups included,
// which a page cannot know for itself.
const WAITING_FOR_ME: DescendantFilter = {
  type: TASK_INSTANCE_NODE_TYPE,
  filters: [
    { name: "performers", value: "@myPrincipals" },
    // Open, not merely present: a completed task is a record of what happened, not something to do
    { name: "status", value: "created" },
  ],
};

// The dashboard widget listing what the person signed in still has to act on, registered on the
// `iap/dashboard/widget` extension point.
function MyTasksWidget() {
  return (
    <EntityDataGrid
      entityType={SUBMISSION_TYPE}
      childFilter={WAITING_FOR_ME}
      emptyMessage="Nothing is waiting for you"
      noResultsMessage="No matching requests"
      searchLabel="Search what is waiting for you"
    />
  );
}

export default MyTasksWidget;
