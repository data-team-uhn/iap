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

import { useCallback, useEffect, useRef, useState } from "react";

import { Alert, Button, CircularProgress, Stack, Typography } from "@mui/material";
import { Link as RouterLink, useNavigate } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import NoticeSnackbar, { type Notice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, messageOf } from "@iap/frontend-commons/requestFailure";

import BpmnEditor from "./BpmnEditor";
import {
  adminUrl,
  loadWorkflow,
  type WorkflowState,
  type WorkflowSummary,
  type WorkflowVersionSummary,
} from "./workflowModel";
import WorkflowStateChip from "./WorkflowStateChip";
import { saveDiagram } from "./workflowWrites";

// Text for the read-only notice, one phrase per non-editable state. DRAFT is included only for
// completeness — it's never actually shown, since a draft is always editable.
const STATE_PHRASES: Record<WorkflowState, string> = {
  DRAFT: "a draft",
  TRIAL: "on trial",
  ACTIVE: "active",
  RETIRED: "retired",
};

interface WorkflowEditorProps {
  // The version's repository path, read out of the URL by the console (see WorkflowConsole)
  path: string;
  // Whether the URL asked for edit mode (?page=edit). Granting it is still this page's decision:
  // only a draft is editable.
  editing: boolean;
}

// The diagram of one workflow version, viewed or edited.
//
// The version is addressed by its repository path, carried in the URL after the console's own prefix
// (/admin/workflows/Workflows/review/1-0), with ?page=edit asking for the editing mode — which is a
// request rather than a grant: only a draft is editable, whatever the URL asks for. The page holds
// the identity — which version this is, what state it is in — and the buttons that save it and move
// on; the canvas below it holds the diagram.
//
// Viewing and editing are the same URL asked two ways, so each offers the way to the other: a draft
// being looked at offers Edit, and the editor saves either where it stands, on its way to the
// viewer, or on its way back to the workflow.
//
// Load, Save-as and New are deliberately absent: this page is opened for one version, from the page
// that manages the workflow, which is where versions are created and chosen between.
function WorkflowEditor({ path, editing }: WorkflowEditorProps) {
  const requestedEdit = editing;
  const navigate = useNavigate();

  const [ workflow, setWorkflow ] = useState<WorkflowSummary>();
  const [ loadError, setLoadError ] = useState<string>();
  const [ dirty, setDirty ] = useState(false);
  const [ saving, setSaving ] = useState(false);
  const [ notice, setNotice ] = useState<Notice>();
  // The canvas hands over the means to serialize what is drawn; null until it is ready, and in view
  // mode, where there is nothing to save
  const serializeRef = useRef<(() => Promise<string>) | null>(null);

  const fetchUtil = useAuthenticatedFetch();

  // The version's own row of the workflow it belongs to: the manager's listing is the one place that
  // knows a version's label and state, and this page needs both to say what is being looked at
  const definitionPath = path.slice(0, path.lastIndexOf("/"));

  const load = useCallback((): Promise<void> =>
    loadWorkflow(fetchUtil, definitionPath)
      .then(loaded => {
        setWorkflow(loaded);
        setLoadError(undefined);
      })
      .catch((error: unknown) => {
        setLoadError(describeRequestFailure(error));
      }), [fetchUtil, definitionPath]);

  useEffect(() => {
    void load();
  }, [load]);

  const onReady = useCallback((serialize: (() => Promise<string>) | null) => {
    serializeRef.current = serialize;
  }, []);

  // Leaving with unsaved changes: the browser's own warning is the only one that can interrupt a
  // reload or a closed tab, and it is enough — the page itself says the same thing in its header.
  useEffect(() => {
    if (!dirty) {
      return undefined;
    }
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const version: WorkflowVersionSummary | undefined =
    workflow?.versions.find(candidate => candidate.path === path);
  // Editable only for a draft. An active or retired version already has other instances relying on
  // it, and a trial is meant to be tried as-is, so anything else opens read-only with an explanation.
  const editable = requestedEdit && version?.state === "DRAFT";

  // Saves the diagram, then navigates to `destination` if one was given and the save succeeded. A
  // refused save stays on the page, reports itself, and offers to retry.
  // Declared rather than assigned, so the failure report can offer to call it again.
  function save(destination?: string): void {
    const serialize = serializeRef.current;
    if (!serialize) {
      // Nothing to serialize, so there is nothing to save, and a save on its way somewhere still goes there
      if (destination !== undefined) {
        void navigate(destination);
      }
      return;
    }
    setSaving(true);
    void serialize()
      .then(xml => saveDiagram(fetchUtil, path, xml))
      .then(() => {
        setDirty(false);
        if (destination === undefined) {
          setNotice({ title: "The diagram was saved", severity: "success" });
        } else {
          // No confirmation: the page it lands on is the confirmation, and a snackbar raised here
          // would be reporting on a page that is already gone
          void navigate(destination);
        }
      })
      .catch((error: unknown) => {
        setNotice({
          title: "The diagram could not be saved",
          message: messageOf(error),
          severity: "error",
          onRetry: () => save(destination),
        });
      })
      .finally(() => setSaving(false));
  }

  const label = version ? version.version || version.name : "";
  const title = workflow ? `${workflow.title}${label === "" ? "" : `: Version ${label}`}` : "Workflow Version";

  return (
    <AdminScreen
      title={title}
      action={
        <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
          { editable && (
            <>
              { dirty && <Typography variant="body2" color="text.secondary">Unsaved changes</Typography> }
              <Button variant="contained" onClick={() => save()} disabled={saving}>
                { saving ? <CircularProgress size={20} /> : "Save" }
              </Button>
              <Button variant="outlined" onClick={() => save(adminUrl(path))} disabled={saving}>
                Save and view
              </Button>
              <Button variant="outlined" onClick={() => save(adminUrl(definitionPath))} disabled={saving}>
                Save and close
              </Button>
            </>
          )}
          { /* Offered only on a draft, and only when the editor is not already open: every other
               state is one something may be following, so there is no editing to offer */ }
          { !requestedEdit && version?.state === "DRAFT" && (
            <Button variant="contained" component={RouterLink} to={adminUrl(path, "edit")}>
              Edit
            </Button>
          )}
        </Stack>
      }
    >
      <Stack spacing={2}>
        { loadError && (
          <LoadError title="This workflow version could not be loaded" message={loadError} onRetry={load} />
        )}
        { workflow && (
          <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" }}>
            { version && <WorkflowStateChip state={version.state} /> }
            { version?.description !== undefined && version.description !== "" && (
              <Typography variant="body2" color="text.secondary">{version.description}</Typography>
            )}
          </Stack>
        )}
        { workflow && !version && (
          <Alert severity="warning">
            This workflow has no version stored at {path}; the diagram below is whatever that path holds.
          </Alert>
        )}
        { requestedEdit && version && !editable && (
          <Alert severity="info">
            Only a draft can be edited. Version {label} is {STATE_PHRASES[version.state]}, so it is shown
            read-only — { version.state === "TRIAL"
              ? "to change what it does, return it to being a draft."
              : "to change what it does, create a new draft from it." }
          </Alert>
        )}
        <BpmnEditor
          versionPath={path}
          editable={editable}
          onDirtyChange={setDirty}
          onReady={onReady}
        />
      </Stack>
      <NoticeSnackbar notice={notice} onClose={() => setNotice(undefined)} />
    </AdminScreen>
  );
}

export default WorkflowEditor;
