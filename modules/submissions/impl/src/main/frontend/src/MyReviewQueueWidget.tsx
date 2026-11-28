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

import { useEffect, useMemo, useState } from "react";

import ExpandLessOutlined from "@mui/icons-material/ExpandLessOutlined";
import ExpandMoreOutlined from "@mui/icons-material/ExpandMoreOutlined";
import { ButtonBase, Stack, Typography } from "@mui/material";

import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import { fetchEntityPage, type DescendantFilter } from "@iap/frontend-commons/entityGrid/pagination";
import type { EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import DiscussionCell from "./review/DiscussionCell";
import NotesCell from "./review/NotesCell";
import { REVIEW_QUEUE_TYPE } from "./review/reviewQueueGrid";
import { useCurrentUser, useMyGroups } from "./review/useCurrentUser";

const OPEN_TASK = [
  { name: "status", value: "created", group: "status" },
  { name: "status", value: "active", group: "status" },
];

// The submissions waiting on one of the reviewer's groups: a review task performed by the group, still open
function yourTurn(groups: string[]): DescendantFilter {
  return {
    type: "wf:TaskInstance",
    filters: [ ...groups.map(group => ({ name: "performers", value: group, group: "performers" })), ...OPEN_TASK ],
  };
}

// The submissions one of the reviewer's groups sent back, while the study team answers: the group's answer task,
// still open. PRETOTYPE: its name follows the demo's workflow, "respond" for the REB, "<approval>Respond" otherwise.
function withTheStudyTeam(approvals: string[]): DescendantFilter {
  return {
    type: "wf:TaskInstance",
    filters: [ ...approvals.map(name => ({ name: "taskDefinitionId", value: name === "reb" ? "respond" : `${name}Respond`,
      group: "task" })), ...OPEN_TASK ],
  };
}

// The approvals the reviewer's groups decide, by name, read from the schemas
function useMyApprovals(groups: string[]): string[] | undefined {
  const doFetch = useAuthenticatedFetch();
  const [ approvals, setApprovals ] = useState<string[]>();
  useEffect(() => {
    if (groups.length === 0) {
      return;
    }
    void doFetch("/Schemas.3.json")
      .then(response => (response.ok ? response.json() : {}) as Promise<Record<string, unknown>>)
      .then(schemas => {
        const found = new Set<string>();
        const visit = (node: Record<string, unknown>) => Object.entries(node).forEach(([ name, child ]) => {
          if (typeof child !== "object" || child === null) {
            return;
          }
          const part = child as Record<string, unknown>;
          if (part["jcr:primaryType"] === "sch:ApprovalRequirement" && groups.includes(String(part.approverGroup))) {
            found.add(name);
          }
          visit(part);
        });
        visit(schemas);
        setApprovals([ ...found ]);
      });
  }, [ doFetch, groups ]);
  return approvals;
}

// How many submissions a filter matches
function useCount(filter: DescendantFilter | undefined): number | undefined {
  const doFetch = useAuthenticatedFetch();
  const [ count, setCount ] = useState<number>();
  const key = JSON.stringify(filter);
  useEffect(() => {
    if (!filter) {
      return;
    }
    let current = true;
    void fetchEntityPage(doFetch, { homepage: "/Submissions", limit: 1, childFilter: filter })
      .then(page => current && setCount(page.totalrows))
      .catch(() => undefined);
    return () => {
      current = false;
    };
  }, [ doFetch, key ]); // eslint-disable-line react-hooks/exhaustive-deps
  return count;
}

// The dashboard widget listing the submissions waiting for the current user's review, registered
// on the `iap/dashboard/widget` extension point. The surrounding titled frame is provided by the
// dashboard, so this only renders the lists. PRETOTYPE: in two, by whose turn it is: what waits on the reviewer,
// the most urgent first; then, folded, what they sent back and the study team is answering.
function MyReviewQueueWidget() {
  const me = useCurrentUser();
  const groups = useMyGroups(me);
  // The groups that review: those deciding an approval
  const approvals = useMyApprovals(groups);
  const reviewing = useMemo(() => groups.filter(group => /(reviewers|heads|professions)$/.test(group)), [ groups ]);
  const mine = reviewing.length > 0 ? yourTurn(reviewing) : undefined;
  const sentBack = approvals && approvals.length > 0 ? withTheStudyTeam(approvals) : undefined;
  const waiting = useCount(sentBack);
  const [ waitingOpen, setWaitingOpen ] = useState(false);
  // PRETOTYPE: the reviewer's own notes and the reviewers' discussion, at a glance, as in their spreadsheets
  const extraColumns: EntityGridColumn[] = useMemo(() => me ? [
    {
      field: "__notes__",
      headerName: "Notes to self",
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
        <DiscussionCell path={params.row["@path"] as string} me={me}
          title={typeof params.row.title === "string" ? params.row.title : ""} />
      ),
    },
  ] : [], [ me ]);

  if (!mine) {
    return null;
  }
  return (
    <Stack spacing={2}>
      <EntityDataGrid
        entityType={REVIEW_QUEUE_TYPE}
        childFilter={mine}
        extraColumns={extraColumns}
        pageSize={25}
        pageSizeOptions={[10, 25, 50]}
        height={640}
        emptyMessage="Nothing waits on you"
        noResultsMessage="No matching submissions"
        searchLabel="Search your queue"
      />
      {sentBack && (waiting ?? 0) > 0 && (
        <Stack spacing={1}>
          <ButtonBase onClick={() => setWaitingOpen(!waitingOpen)} aria-expanded={waitingOpen}
            sx={{ alignSelf: "flex-start", gap: 0.5, px: 0.5, borderRadius: 1 }}>
            {waitingOpen ? <ExpandLessOutlined fontSize="small" /> : <ExpandMoreOutlined fontSize="small" />}
            <Typography variant="subtitle2" component="h3">With the study team ({waiting})</Typography>
          </ButtonBase>
          <Typography variant="caption" sx={{ color: "text.secondary" }}>
            Sent back for changes; they come back to your turn once the study team answers.
          </Typography>
          {waitingOpen && (
            <EntityDataGrid
              entityType={REVIEW_QUEUE_TYPE}
              childFilter={sentBack}
              extraColumns={extraColumns}
              pageSize={10}
              pageSizeOptions={[10, 25]}
              height={360}
              emptyMessage="Nothing is with the study team"
              noResultsMessage="No matching submissions"
              searchLabel="Search what is with the study team"
            />
          )}
        </Stack>
      )}
    </Stack>
  );
}

export default MyReviewQueueWidget;
