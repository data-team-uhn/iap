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

import { useCallback, useEffect, useState, type ReactNode } from "react";

import AddIcon from "@mui/icons-material/Add";
import EditIcon from "@mui/icons-material/Edit";
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useNavigate } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import NoticeSnackbar, { type Notice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure } from "@iap/frontend-commons/requestFailure";

import NewVersionDialog from "./NewVersionDialog";
import { adminUrl, loadWorkflow, type WorkflowSummary } from "./workflowModel";
import WorkflowPropertiesDialog from "./WorkflowPropertiesDialog";
import WorkflowStateChip from "./WorkflowStateChip";
import WorkflowVersionActions from "./WorkflowVersionActions";

// A repository timestamp as a sentence-worthy date, or nothing at all when it is absent.
function formatDate(value: string): string {
  return value === "" ? "" : new Date(value).toLocaleString();
}

// One labelled property of the workflow.
function Property({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
      <Typography variant="caption" color="text.secondary" sx={{ minWidth: 120 }}>{label}</Typography>
      <Box sx={{ typography: "body2" }}>{children}</Box>
    </Stack>
  );
}

interface WorkflowManagerProps {
  // The workflow's repository path, read out of the URL by the console (see WorkflowConsole)
  path: string;
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
// The per-version buttons are deliberately not written here: they are contributed on the
// WorkflowVersionActions extension point, so an action added later needs no change to this file.
function WorkflowManager({ path }: WorkflowManagerProps) {
  const navigate = useNavigate();
  const [ workflow, setWorkflow ] = useState<WorkflowSummary>();
  const [ loadError, setLoadError ] = useState<string>();
  const [ editing, setEditing ] = useState(false);
  const [ addingVersion, setAddingVersion ] = useState(false);
  const [ notice, setNotice ] = useState<Notice>();
  const fetchUtil = useAuthenticatedFetch();

  const load = useCallback((): Promise<void> =>
    loadWorkflow(fetchUtil, path)
      .then(loaded => {
        setWorkflow(loaded);
        setLoadError(undefined);
      })
      .catch((error: unknown) => {
        setLoadError(describeRequestFailure(error));
      }), [fetchUtil, path]);

  useEffect(() => {
    void load();
  }, [load]);

  const openNewVersion = (versionPath: string): void => {
    setAddingVersion(false);
    void navigate(adminUrl(versionPath, "edit"));
  };

  if (loadError) {
    return (
      <AdminScreen title="Workflow">
        <LoadError title="This workflow could not be loaded" message={loadError} onRetry={load} />
      </AdminScreen>
    );
  }
  if (!workflow) {
    return (
      <AdminScreen title="Workflow">
        <CircularProgress size={24} sx={{ display: "block", mx: "auto", my: 2 }} />
      </AdminScreen>
    );
  }

  return (
    <AdminScreen
      title={workflow.title}
      action={
        <Stack direction="row" spacing={1}>
          <Button variant="outlined" startIcon={<EditIcon />} onClick={() => setEditing(true)}>
            Edit properties
          </Button>
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setAddingVersion(true)}>
            New version
          </Button>
        </Stack>
      }
    >
      <Stack spacing={3}>
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="h6" gutterBottom>Properties</Typography>
          <Stack spacing={1}>
            <Property label="Title">{workflow.title}</Property>
            <Property label="Stored at">{workflow.path}</Property>
            <Property label="Runs">
              { workflow.active
                ? <Chip size="small" color="success" label="Enabled" />
                : <Chip size="small" variant="outlined" label="Disabled" /> }
            </Property>
            { workflow.created !== "" && <Property label="Created">{formatDate(workflow.created)}</Property> }
            { workflow.lastModified !== ""
              && <Property label="Last modified">{formatDate(workflow.lastModified)}</Property> }
          </Stack>
        </Paper>

        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="h6" gutterBottom>Versions</Typography>
          { workflow.versions.length === 0
            ? <Typography color="text.secondary">This workflow has no versions yet.</Typography>
            : (
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Version</TableCell>
                    <TableCell>State</TableCell>
                    <TableCell>Description</TableCell>
                    <TableCell>Last modified</TableCell>
                    <TableCell align="right">Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  { workflow.versions.map(version => (
                    <TableRow key={version.path}>
                      <TableCell>{version.version || version.name}</TableCell>
                      <TableCell><WorkflowStateChip state={version.state} /></TableCell>
                      <TableCell>{version.description}</TableCell>
                      <TableCell>{formatDate(version.lastModified)}</TableCell>
                      <TableCell align="right">
                        <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
                          <WorkflowVersionActions
                            version={version}
                            workflow={workflow}
                            reload={() => void load()}
                            report={message => setNotice({ title: message, severity: "success" })}
                          />
                        </Stack>
                      </TableCell>
                    </TableRow>
                  )) }
                </TableBody>
              </Table>
            )}
        </Paper>
      </Stack>

      { editing && (
        <WorkflowPropertiesDialog
          workflow={workflow}
          onClose={() => setEditing(false)}
          onSaved={() => void load()}
        />
      )}
      { addingVersion && (
        <NewVersionDialog
          workflow={workflow}
          onClose={() => setAddingVersion(false)}
          onCreated={openNewVersion}
        />
      )}
      <NoticeSnackbar notice={notice} onClose={() => setNotice(undefined)} />
    </AdminScreen>
  );
}

export default WorkflowManager;
