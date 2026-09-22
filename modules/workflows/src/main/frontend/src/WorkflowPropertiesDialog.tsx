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

import { Alert, Button, DialogActions, DialogContent, Stack, TextField } from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import { updateWorkflow } from "./workflowWrites";

import type { WorkflowSummary } from "./workflowModel";

interface WorkflowPropertiesDialogProps {
  workflow: WorkflowSummary;
  onClose: () => void;
  onSaved: () => void;
}

// Edits the workflow's own properties — currently just the title.
// Whether it runs isn't one of them: that's read from its versions, and changed through their own actions.
function WorkflowPropertiesDialog({ workflow, onClose, onSaved }: WorkflowPropertiesDialogProps) {
  const [ title, setTitle ] = useState(workflow.title);
  const fetchUtil = useAuthenticatedFetch();
  const { working, failure, run } = useAsyncAction<string>({
    onFailure: messageOf,
    onSuccess: () => {
      onSaved();
      onClose();
    },
  });

  const save = (): Promise<void> =>
    updateWorkflow(fetchUtil, workflow.path, { title: title.trim() });

  return (
    <ResponsiveDialog
      open
      title="Workflow properties"
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
          { failure && <Alert severity="error">{failure}</Alert> }
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={working}>Cancel</Button>
        <Button variant="contained" onClick={() => run(save)} disabled={working || title.trim() === ""}>
          Save
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default WorkflowPropertiesDialog;
