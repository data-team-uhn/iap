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

import { useEffect, useState } from "react";

import AddIcon from "@mui/icons-material/Add";
import { Button, CircularProgress, Stack, Tab, Tabs, Typography } from "@mui/material";
import { useNavigate } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import NewWorkflowDialog from "./NewWorkflowDialog";
import { WORKFLOW_TYPE } from "./workflowGrid";
import { adminUrl, loadWorkflowHomepages, type WorkflowHomepage } from "./workflowModel";

// The "Workflows" administrative tool: every workflow this instance can see, in the shared
// administration chrome (routed at /admin/workflows, see the extension node under
// Extensions/Admin/Views). Picking one opens the page that manages it and its versions.
//
// Which tab is open is in the URL — /admin/workflows/SystemWorkflows — so that the homepage a
// workflow is stored in is a page of its own rather than a segment of its URL that leads nowhere.
//
// The homepages workflows live in are discovered rather than assumed — a deployment holding the
// platform's own under /SystemWorkflows, or another location's mirrored locally, has more than one —
// and each gets its own grid, one at a time, behind a tab bearing its name. One grid per homepage
// keeps every listing a plain query over one tree, so a page of workflows always says where its
// workflows are stored, and only the tab being looked at is fetched.
interface WorkflowsViewProps {
  // The homepage to open on, when the URL named one: /admin/workflows/SystemWorkflows. Absent at
  // /admin/workflows itself, which opens on whichever homepage was discovered first.
  homepage?: string;
}

function WorkflowsView({ homepage: opened }: WorkflowsViewProps) {
  const [ homepages, setHomepages ] = useState<WorkflowHomepage[]>();
  const [ creating, setCreating ] = useState(false);
  const fetchUtil = useAuthenticatedFetch();
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;
    void loadWorkflowHomepages(fetchUtil).then(discovered => {
      if (!cancelled) {
        setHomepages(discovered);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [fetchUtil]);

  // A workflow just created has one draft version and nothing drawn in it yet, so the editor is
  // where its author is going next
  const openNewWorkflow = (versionPath: string): void => {
    setCreating(false);
    void navigate(adminUrl(versionPath, "edit"));
  };

  // Creating needs somewhere to create in: the action waits for the discovery like the grids do,
  // and stays out of reach if it turned up nowhere this user may store a workflow
  const action = (
    <Button
      variant="contained"
      startIcon={<AddIcon />}
      onClick={() => setCreating(true)}
      disabled={homepages === undefined || homepages.length === 0}
    >
      New workflow
    </Button>
  );

  // A lone homepage is the page's own subject: a single tab would name what the heading already says
  const several = (homepages?.length ?? 0) > 1;
  // The tab that is open, resolved against what was actually discovered rather than trusted: nothing
  // is open until the discovery lands, and a homepage that stopped being readable stops being shown.
  // Read from the URL rather than held here, so that there is one answer rather than two that can
  // disagree — picking a tab navigates, and what comes back is what is shown.
  const listed = homepages?.some(homepage => homepage.path === opened) ? opened : homepages?.[0]?.path;

  return (
    <AdminScreen title="Workflows" action={action}>
      { homepages === undefined
        ? <CircularProgress size={24} sx={{ display: "block", mx: "auto", my: 2 }} />
        : homepages.length === 0
          ? (
            <Typography color="textSecondary">
              There is nowhere you can read workflows from.
            </Typography>
          )
          : (
            <Stack spacing={2}>
              { several && (
                <Tabs
                  value={listed}
                  // The tab is in the URL, so switching tabs is a navigation: what is open can
                  // then be linked to, gone back from, and read by the breadcrumbs above
                  onChange={(_event, picked: string) => void navigate(adminUrl(picked))}
                  variant="scrollable"
                  scrollButtons="auto"
                  aria-label="Where workflows are stored"
                >
                  { homepages.map(homepage => (
                    <Tab key={homepage.path} value={homepage.path} label={homepage.title} />
                  )) }
                </Tabs>
              )}
              { listed !== undefined && (
                <EntityDataGrid
                  // Keyed by homepage so that switching tabs starts the listing over — the page,
                  // the sort and the search belong to the tree being listed, not to the page
                  key={listed}
                  entityType={WORKFLOW_TYPE}
                  homepage={listed}
                  pageSize={10}
                  height={600}
                  emptyMessage={several ? "No workflows are stored here yet" : "No workflows are defined yet"}
                  noResultsMessage="No matching workflows"
                />
              )}
            </Stack>
          )}
      { creating && homepages && (
        <NewWorkflowDialog
          homepages={homepages}
          onClose={() => setCreating(false)}
          onCreated={openNewWorkflow}
        />
      )}
    </AdminScreen>
  );
}

export default WorkflowsView;
