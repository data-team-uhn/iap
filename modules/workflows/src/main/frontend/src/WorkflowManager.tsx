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

import { useCallback, type ReactNode } from "react";

import { Box, Chip, Stack, Typography } from "@mui/material";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import Panel from "@iap/frontend-commons/components/Panel";
import { usePageCrumbs } from "@iap/frontend-commons/pageCrumbs";

import { useWorkflow } from "./useWorkflow";
import WorkflowActions from "./WorkflowActions";
import { adminUrl, type WorkflowHomepage, type WorkflowSummary } from "./workflowModel";
import WorkflowVersionList from "./WorkflowVersionList";

// A repository timestamp as a sentence-worthy date, or nothing at all when it is absent.
function formatDate(value: string): string {
  return value === "" ? "" : new Date(value).toLocaleString();
}

function Property({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
      <Typography variant="caption" sx={{ minWidth: 120 }}>{label}</Typography>
      <Box sx={{ typography: "body2" }}>{children}</Box>
    </Stack>
  );
}

// Whether new instances start from a workflow, which is read off its versions: what its page says it is.
function RunsChip({ workflow }: { workflow: WorkflowSummary }) {
  if (workflow.active) {
    return <Chip size="small" color="success" label="Enabled" />;
  }
  return workflow.retired
    ? <Chip size="small" color="warning" variant="outlined" label="Retired" />
    : <Chip size="small" variant="outlined" label="Disabled" />;
}

interface WorkflowManagerProps {
  // The workflow's repository path, read out of the URL by the console (see WorkflowConsole)
  path: string;
  // The homepage it is stored in, which the breadcrumb trail leads back to
  homepage: WorkflowHomepage;
}

// The page managing one workflow: its own properties, and every version of it with the actions that
// apply to each.
//
// The workflow is addressed by its repository path, carried in the URL after the console's own
// prefix (/admin/workflows/Workflows/review), which is what lets this one page manage the workflows
// of any homepage — this location's, the platform's own, another location's — without a route per
// tree. The URL is read by the console rather than here: which of the three things a console URL is
// about takes the list of homepages, and asking for it once is what keeps this page a function of
// the path it is given.
//
// The buttons are deliberately not written here: the workflow's own are contributed on the
// WorkflowActions extension point and each version's on WorkflowVersionActions, so an action added
// later needs no change to this file.
function WorkflowManager({ path, homepage }: WorkflowManagerProps) {
  const { workflow, loading, loadError, reload } = useWorkflow(path);
  const reloadWorkflow = useCallback(() => void reload(), [ reload ]);
  usePageCrumbs([ { path: adminUrl(homepage.path), label: homepage.title } ]);

  if (!workflow) {
    return (
      <AdminScreen title="Workflow">
        <LoadingOverlay open={loading} />
        { loadError && <LoadError title="This workflow could not be loaded" message={loadError} onRetry={reload} /> }
      </AdminScreen>
    );
  }

  return (
    <AdminScreen
      title={workflow.title}
      status={<RunsChip workflow={workflow} />}
      description={"Only a draft version can be edited. A version on trial is changed by returning it to being a "
        + "draft, and an active or retired one by drafting a copy of it, which takes over once activated."}
      disablePanel
      action={<WorkflowActions workflow={workflow} reload={reloadWorkflow} />}
    >
      <Stack spacing={3}>
        { loadError && <LoadError title="The workflow could not be reloaded" message={loadError} onRetry={reload} /> }
        <Panel title="Properties">
          <Stack spacing={1}>
            <Property label="Stored at">{workflow.path}</Property>
            { workflow.created !== "" && <Property label="Created">{formatDate(workflow.created)}</Property> }
            { workflow.lastModified !== ""
              && <Property label="Last modified">{formatDate(workflow.lastModified)}</Property> }
          </Stack>
        </Panel>
        <Panel title="Versions">
          <WorkflowVersionList workflow={workflow} reload={reloadWorkflow} />
        </Panel>
      </Stack>
    </AdminScreen>
  );
}

export default WorkflowManager;
