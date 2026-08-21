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
import { TASK_INSTANCE_NODE_TYPE } from "./submissionTasks";

// Only the submissions with something still open that names one of the principals the reader acts as.
//
// Asked of tasks rather than of reviews, and that is the design rather than an implementation detail:
// a `sub:Review` is what a task *produces*, while a `wf:TaskInstance` is the process asking for it.
// Everything a person can owe — a review to submit, a document to re-read, a decision to make — is a
// task some process raised, so there is one question to ask instead of one table per kind of thing.
//
// `@myPrincipals` is expanded server-side into an OR over everything the session acts as, because
// `performers` names principals — a whole team as readily as one person — and a page cannot know its
// own group memberships to build that itself.
const WAITING_FOR_ME: DescendantFilter = {
  type: TASK_INSTANCE_NODE_TYPE,
  filters: [
    { name: "performers", value: "@myPrincipals" },
    // Open, not merely present: a completed task is a record of what happened, not something to do
    { name: "status", value: "created" },
  ],
};

// The dashboard widget listing what the person signed in still has to act on, registered on the
// `iap/dashboard/widget` extension point. The surrounding titled frame is provided by the dashboard,
// so this only renders the grid.
//
// One row per request even when several things are waiting on it: the row is what a person recognises
// and opens, and the pagination servlet already reduces the duplicates a descendant join produces.
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
