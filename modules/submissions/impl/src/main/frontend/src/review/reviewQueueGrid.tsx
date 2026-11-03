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

import { Stack } from "@mui/material";

import { type EntityGridColumn, registerEntityType } from "@iap/frontend-commons/entityGrid/registry";
import TagChip from "@iap/tags/TagChip";
import { tagValueOptions } from "@iap/tags/tagDefinitions";

import Countdown from "./Countdown";

// PRETOTYPE. Submissions as a reviewer's queue lists them: what they need to know at a glance, the most urgent
// first. Fast-tracked studies have a shorter deadline, so ordering by deadline puts them where they belong.
export const REVIEW_QUEUE_TYPE = "sub/Submission/reviewQueue";

const COLUMNS: EntityGridColumn[] = [
  { field: "identifier", headerName: "ID", width: 110, cardSlot: "caption" },
  {
    field: "title",
    headerName: "Title",
    flex: 2,
    minWidth: 200,
    cardSlot: "title",
    cardValue: row => (row.title as string | undefined) ?? (row["@name"] as string | undefined),
  },
  { field: "pi", headerName: "PI", flex: 1, minWidth: 150, cardSlot: "caption" },
  {
    field: "tags",
    headerName: "Status",
    width: 200,
    type: "singleSelect",
    valueOptions: tagValueOptions("lifecycle"),
    sortable: false,
    renderCell: params => (
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.5, height: "100%" }}>
        <TagChip tags={params.row.tags} category="lifecycle" />
        <TagChip tags={params.row.tags} category="priority" />
      </Stack>
    ),
    cardSlot: "badge",
  },
  {
    field: "reviewDue",
    headerName: "Time left",
    width: 170,
    type: "dateTime",
    filterable: false,
    valueGetter: value => typeof value === "string" ? new Date(value) : null,
    renderCell: params => (
      <Stack sx={{ justifyContent: "center", height: "100%" }}>
        <Countdown due={params.row.reviewDue} pausedAt={params.row.reviewPausedAt} />
      </Stack>
    ),
    cardSlot: "row",
  },
];

registerEntityType(REVIEW_QUEUE_TYPE, {
  homepage: "/Submissions",
  columns: COLUMNS,
  defaultSort: { field: "reviewDue", sort: "asc" },
  rowLink: row => typeof row["@path"] === "string" ? `${row["@path"]}.review` : undefined,
});
