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
import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { usePageCrumbs } from "@iap/frontend-commons/pageCrumbs";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import LifecycleChip from "@iap/tags/LifecycleChip";

import BpmnEditor from "./BpmnEditor";
import { useWorkflow } from "./useWorkflow";
import { adminUrl, offers, type WorkflowHomepage, type WorkflowVersionSummary } from "./workflowModel";
import { saveDiagram } from "./workflowWrites";

interface WorkflowEditorProps {
  // The version's repository path, read out of the URL by the console (see WorkflowConsole)
  path: string;
  // The homepage its workflow is stored in, which the breadcrumb trail leads back to
  homepage: WorkflowHomepage;
  // Whether the URL asked for edit mode (the .edit suffix). Granting it is still this page's decision:
  // only a draft is editable.
  editing: boolean;
}

// The diagram of one workflow version, viewed or edited.
//
// The version is addressed by its repository path, carried in the URL after the console's own prefix
// (/admin/workflows/Workflows/review/v1), with a .edit suffix asking for the editing mode — which is a
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
function WorkflowEditor({ path, homepage, editing }: WorkflowEditorProps) {
  const requestedEdit = editing;
  const navigate = useNavigate();

  const [ dirty, setDirty ] = useState(false);
  const [ saving, setSaving ] = useState(false);
  const notify = useNotice();
  // The canvas hands over the means to serialize what is drawn; null until it is ready, and in view
  // mode, where there is nothing to save
  const serializeRef = useRef<(() => Promise<string>) | null>(null);

  const fetchUtil = useAuthenticatedFetch();

  // The version's own row of the workflow it belongs to: the manager's listing is the one place that
  // knows a version's label and state, and this page needs both to say what is being looked at
  const definitionPath = path.slice(0, path.lastIndexOf("/"));

  const { workflow, loadError, reload } = useWorkflow(definitionPath);
  // The steps above this page that its path cannot name: the homepage, and the workflow under its title
  // once it is read
  const workflowName = definitionPath.slice(definitionPath.lastIndexOf("/") + 1);
  usePageCrumbs([
    { path: adminUrl(homepage.path), label: homepage.title },
    { path: adminUrl(definitionPath), label: workflow?.title ?? workflowName },
  ]);

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
  // Editable where the server offers to save the diagram, which is on a draft. Anything else opens
  // read-only, with an explanation.
  const editable = requestedEdit && version !== undefined && offers(version, "save");

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
          notify({ title: "The diagram was saved", severity: "success" });
        } else {
          // No confirmation: the page it lands on is the confirmation
          void navigate(destination);
        }
      })
      .catch((error: unknown) => {
        notify({
          title: "The diagram could not be saved",
          message: messageOf(error),
          severity: "error",
          onRetry: () => save(destination),
        });
      })
      .finally(() => setSaving(false));
  }

  const label = version ? version.version || version.name : path.slice(path.lastIndexOf("/") + 1);

  return (
    <AdminScreen
      title={`Version ${label}`}
      titlePrefix={workflow?.title}
      status={version && <LifecycleChip tags={version.tags} />}
      description={version?.description}
      action={
        <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
          { editable && (
            <>
              { dirty && <Typography variant="description">Unsaved changes</Typography> }
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
          { /* Offered where saving is, and only when the editor is not already open */ }
          { !requestedEdit && version !== undefined && offers(version, "save") && (
            <Button variant="contained" component={RouterLink} to={adminUrl(path, "edit")}>
              Edit
            </Button>
          )}
        </Stack>
      }
    >
      <Stack spacing={2}>
        { loadError && (
          <LoadError title="This workflow version could not be loaded" message={loadError} onRetry={reload} />
        )}
        { workflow && !version && (
          <Alert severity="warning">
            This workflow has no version stored at {path}; the diagram below is whatever that path holds.
          </Alert>
        )}
        { requestedEdit && version && !editable && (
          <Alert severity="info">
            Only a draft can be edited, so version {label} is shown read-only —
            { offers(version, "returnToDraft")
              ? " to change what it does, return it to being a draft."
              : " to change what it does, create a new draft from it." }
          </Alert>
        )}
        <BpmnEditor
          versionPath={path}
          editable={editable}
          onDirtyChange={setDirty}
          onReady={onReady}
        />
      </Stack>
    </AdminScreen>
  );
}

export default WorkflowEditor;
