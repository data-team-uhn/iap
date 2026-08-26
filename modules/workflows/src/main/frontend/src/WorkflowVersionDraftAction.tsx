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

import { useState } from "react";

import ContentCopyIcon from "@mui/icons-material/ContentCopy";
import { Alert, Button, DialogActions, DialogContent, DialogContentText, Stack, TextField } from "@mui/material";
import { useNavigate } from "react-router";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import { adminUrl } from "./workflowModel";
import { draftFromVersion } from "./workflowWrites";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Starts a new draft from a version that can no longer be edited itself: the active one, or one
// already retired. This is how a running process is changed — the copy is authored while the original
// keeps running, and takes over only when it is activated.
//
// Not offered for a draft, which can simply be edited.
function WorkflowVersionDraftAction({ version, workflow, reload }: WorkflowVersionActionProps) {
  const [ naming, setNaming ] = useState(false);
  const [ label, setLabel ] = useState("");
  const navigate = useNavigate();
  const fetchUtil = useAuthenticatedFetch();
  const { working, failure, run } = useAsyncAction<string>({
    onFailure: messageOf,
    onSuccess: () => setNaming(false),
  });

  if (version.state === "DRAFT") {
    return null;
  }

  const source = version.version || version.name;
  const used = workflow.versions.map(existing => existing.version);
  const duplicate = label.trim() !== "" && used.includes(label.trim());
  const valid = label.trim() !== "" && !duplicate;

  const draft = (): Promise<void> =>
    draftFromVersion(fetchUtil, version.path, label.trim()).then(created => {
      // Straight into the editor: a draft that was just copied exists to be changed
      reload();
      void navigate(adminUrl(created, "edit"));
    });

  return (
    <>
      <Button size="small" startIcon={<ContentCopyIcon />} onClick={() => setNaming(true)}>
        New draft from this
      </Button>
      { naming && (
        <ResponsiveDialog
          open
          title={`New draft from version ${source}`}
          width="sm"
          withCloseButton
          closeDisabled={working}
          onClose={() => setNaming(false)}
        >
          <DialogContent dividers>
            <Stack spacing={2} sx={{ pt: 1 }}>
              <DialogContentText>
                The new version starts as a draft holding a copy of version {source}&apos;s diagram. Version {source}
                {" "}is left as it is, and keeps running until the draft is activated in its place.
              </DialogContentText>
              <TextField
                label="Version"
                required
                fullWidth
                value={label}
                onChange={event => setLabel(event.target.value)}
                placeholder="e.g. 2.0"
                error={duplicate}
                helperText={duplicate ? "This workflow already has a version with that label" : undefined}
              />
              { failure && <Alert severity="error">{failure}</Alert> }
            </Stack>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setNaming(false)} disabled={working}>Cancel</Button>
            <Button variant="contained" onClick={() => run(draft)} disabled={working || !valid}>
              Create draft
            </Button>
          </DialogActions>
        </ResponsiveDialog>
      )}
    </>
  );
}

export default WorkflowVersionDraftAction;
