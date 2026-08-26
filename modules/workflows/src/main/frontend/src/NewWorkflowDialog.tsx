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

import { Alert, Button, DialogActions, DialogContent, MenuItem, Stack, TextField } from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import { createWorkflow } from "./workflowWrites";

import type { WorkflowHomepage } from "./workflowModel";

interface NewWorkflowDialogProps {
  // The homepages a workflow may be created in; the first is offered by default, and the picker is
  // only shown when there is more than one to choose between.
  homepages: WorkflowHomepage[];
  onClose: () => void;
  // Called with the new draft version's path once the workflow exists, for the caller to open it.
  onCreated: (versionPath: string) => void;
}

// Creating a workflow: its title, an optional description of what the first version does, and that
// version's label. The diagram is not asked for — the workflow is created with a small starting
// diagram and the editor opens on it, which is where a diagram is authored.
//
// There is deliberately no "active" choice here either: a new workflow's first version is a draft,
// and promoting it is a separate, explicit decision made from the workflow's own page.
function NewWorkflowDialog({ homepages, onClose, onCreated }: NewWorkflowDialogProps) {
  const [ title, setTitle ] = useState("");
  const [ description, setDescription ] = useState("");
  const [ version, setVersion ] = useState("1.0");
  const [ homepage, setHomepage ] = useState(homepages[0]?.path ?? "");
  const fetchUtil = useAuthenticatedFetch();
  const { working, failure, run } = useAsyncAction<string>({ onFailure: messageOf, onSuccess: onClose });

  const valid = title.trim() !== "" && version.trim() !== "" && homepage !== "";

  const save = (): Promise<void> =>
    createWorkflow(fetchUtil, {
      homepage,
      title: title.trim(),
      version: version.trim(),
      description: description.trim(),
    }).then(onCreated);

  return (
    <ResponsiveDialog
      open
      title="New workflow"
      width="sm"
      withCloseButton
      closeDisabled={working}
      onClose={onClose}
    >
      <DialogContent dividers>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <TextField
            label="Title"
            required
            fullWidth
            value={title}
            onChange={event => setTitle(event.target.value)}
          />
          <TextField
            label="Version"
            required
            fullWidth
            value={version}
            onChange={event => setVersion(event.target.value)}
            placeholder="e.g. 1.0"
            helperText="The label of the first version, which is created as a draft"
          />
          <TextField
            label="Description"
            fullWidth
            multiline
            rows={2}
            value={description}
            onChange={event => setDescription(event.target.value)}
          />
          { homepages.length > 1 && (
            <TextField
              label="Stored in"
              select
              fullWidth
              value={homepage}
              onChange={event => setHomepage(event.target.value)}
            >
              { homepages.map(option => (
                <MenuItem key={option.path} value={option.path}>{option.title}</MenuItem>
              )) }
            </TextField>
          )}
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

export default NewWorkflowDialog;
