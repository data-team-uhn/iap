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

import { useMemo } from "react";

import { Stack } from "@mui/material";

import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { DescendantFilter } from "@iap/frontend-commons/entityGrid/pagination";
import type { EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";

import DiscussionCell from "./review/DiscussionCell";
import NotesCell from "./review/NotesCell";
import { REVIEW_QUEUE_TYPE } from "./review/reviewQueueGrid";
import { useCurrentUser, useMyGroups } from "./review/useCurrentUser";
import WhoseTurn from "./review/WhoseTurn";

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
  const me = useCurrentUser();
  const myGroups = useMyGroups(me);
  // PRETOTYPE: the reviewer's own notes and the reviewers' discussion, at a glance, as in their spreadsheets
  const extraColumns: EntityGridColumn[] = useMemo(() => me ? [
    {
      // Whose move it is, read from the study's open tasks
      field: "__turn__",
      headerName: "Whose turn",
      width: 190,
      sortable: false,
      filterable: false,
      cardSlot: "badge",
      renderCell: params => (
        <Stack sx={{ justifyContent: "center", height: "100%" }}>
          <WhoseTurn path={params.row["@path"] as string} myGroups={myGroups} />
        </Stack>
      ),
    },
    {
      field: "__notes__",
      headerName: "My notes",
      flex: 1.5,
      minWidth: 200,
      cardSlot: "omit",
      renderCell: params => <NotesCell path={params.row["@path"] as string} me={me} />,
    },
    {
      field: "__discussion__",
      headerName: "Discussion",
      flex: 1.5,
      minWidth: 200,
      cardSlot: "omit",
      renderCell: params => (
        <DiscussionCell path={params.row["@path"] as string} me={me} title={typeof params.row.title === "string" ? params.row.title : ""} />
      ),
    },
  ] : [], [ me, myGroups ]);

  return (
    <EntityDataGrid
      entityType={REVIEW_QUEUE_TYPE}
      childFilter={MY_OPEN_REVIEWS}
      extraColumns={extraColumns}
      pageSize={25}
      pageSizeOptions={[10, 25, 50]}
      height={720}
      emptyMessage="No submissions to review"
      noResultsMessage="No matching submissions"
      searchLabel="Search your queue"
    />
  );
}

export default MyReviewQueueWidget;
