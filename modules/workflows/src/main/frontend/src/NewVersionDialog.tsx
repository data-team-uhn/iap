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

import { Alert, Button, DialogActions, DialogContent, DialogContentText, Stack, TextField } from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import { createVersion } from "./workflowWrites";

import type { WorkflowSummary } from "./workflowModel";

interface NewVersionDialogProps {
  workflow: WorkflowSummary;
  onClose: () => void;
  // Called with the new draft's path, for the caller to open it.
  onCreated: (versionPath: string) => void;
}

// Starts a version from the shipped starting diagram.
// Carrying an existing version's diagram forward instead is the separate "draft a copy" flow.
function NewVersionDialog({ workflow, onClose, onCreated }: NewVersionDialogProps) {
  const [ version, setVersion ] = useState("");
  const [ description, setDescription ] = useState("");
  const fetchUtil = useAuthenticatedFetch();
  const { working, failure, run } = useAsyncAction<string>({ onFailure: messageOf, onSuccess: onClose });

  const used = workflow.versions.map(existing => existing.version);
  const duplicate = version.trim() !== "" && used.includes(version.trim());
  const valid = version.trim() !== "" && !duplicate;

  const save = (): Promise<void> =>
    createVersion(fetchUtil, workflow.path, { version: version.trim(), description: description.trim() })
      .then(onCreated);

  return (
    <ResponsiveDialog
      open
      title={`New version of ${workflow.title}`}
      width="sm"
      withCloseButton
      closeDisabled={working}
      onClose={onClose}
    >
      <DialogContent dividers>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <DialogContentText>
            The new version starts as a draft, with a small starting diagram to author from. To carry an existing
            version&apos;s diagram forward instead, draft a copy of that version.
          </DialogContentText>
          <TextField
            label="Version"
            required
            fullWidth
            value={version}
            onChange={event => setVersion(event.target.value)}
            placeholder="e.g. 2.0"
            error={duplicate}
            helperText={duplicate ? "This workflow already has a version with that label" : undefined}
          />
          <TextField
            label="Description"
            fullWidth
            multiline
            rows={2}
            value={description}
            onChange={event => setDescription(event.target.value)}
          />
          { failure && <Alert severity="error">{failure}</Alert> }
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={working}>Cancel</Button>
        <Button variant="contained" onClick={() => run(save)} disabled={working || !valid}>Create</Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default NewVersionDialog;
