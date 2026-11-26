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

import { DialogContentText, TextField } from "@mui/material";

import FormDialog from "@iap/frontend-commons/components/FormDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import useNewVersionLabel from "./useNewVersionLabel";
import { createVersion } from "./workflowWrites";

import type { WorkflowSummary } from "./workflowModel";

interface NewVersionDialogProps {
  workflow: WorkflowSummary;
  onClose: () => void;
  // Called with the new draft's path, for the caller to open it.
  onCreated: (versionPath: string) => void;
}

// Starts a version from the shipped starting diagram.
function NewVersionDialog({ workflow, onClose, onCreated }: NewVersionDialogProps) {
  const version = useNewVersionLabel(workflow);
  const [ description, setDescription ] = useState("");
  const fetchUtil = useAuthenticatedFetch();

  const save = (): Promise<void> =>
    createVersion(fetchUtil, workflow.path, { version: version.label, description: description.trim() })
      .then(onCreated);

  return (
    <FormDialog
      title={`New version of ${workflow.title}`}
      submitLabel="Create"
      valid={version.valid}
      onSubmit={save}
      onClose={onClose}
    >
      <DialogContentText>
        {"The new version starts as a draft, with a small starting process to author from. To carry an existing "
          + "version's process forward instead, draft a copy of that version."}
      </DialogContentText>
      <TextField {...version.field} />
      <TextField
        label="Description"
        fullWidth
        multiline
        rows={2}
        value={description}
        onChange={event => setDescription(event.target.value)}
      />
    </FormDialog>
  );
}

export default NewVersionDialog;
