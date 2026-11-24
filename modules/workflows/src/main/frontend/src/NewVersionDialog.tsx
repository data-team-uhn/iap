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
import { nextVersionLabel } from "@iap/frontend-commons/versionNumbers";

import { versionLabel } from "./workflowModel";
import { createVersion, draftFromVersion } from "./workflowWrites";

import type { WorkflowSummary, WorkflowVersionSummary } from "./workflowModel";

interface NewVersionDialogProps {
  workflow: WorkflowSummary;
  // The version to copy into the new draft. Without one, the new version starts from the shipped starting
  // diagram.
  source?: WorkflowVersionSummary;
  onClose: () => void;
  // Called with the new draft's path, for the caller to open it.
  onCreated: (versionPath: string) => void;
}

// Starts a version, from the shipped starting diagram or as a copy of `source`.
function NewVersionDialog({ workflow, source, onClose, onCreated }: NewVersionDialogProps) {
  const [ version, setVersion ] = useState(() => nextVersionLabel(workflow.versions.map(existing => existing.name)));
  const [ description, setDescription ] = useState("");
  const fetchUtil = useAuthenticatedFetch();

  const used = workflow.versions.map(existing => existing.version);
  const duplicate = version.trim() !== "" && used.includes(version.trim());
  const valid = version.trim() !== "" && !duplicate;

  const save = (): Promise<void> => (source
    ? draftFromVersion(fetchUtil, source.path, version.trim())
    : createVersion(fetchUtil, workflow.path, { version: version.trim(), description: description.trim() })
  ).then(onCreated);

  return (
    <FormDialog
      title={source ? `New draft from version ${versionLabel(source)}` : `New version of ${workflow.title}`}
      submitLabel={source ? "Create draft" : "Create"}
      valid={valid}
      onSubmit={save}
      onClose={onClose}
    >
      <DialogContentText>
        { source
          ? `The new version starts as a draft holding a copy of version ${versionLabel(source)}. Version `
            + `${versionLabel(source)} is left as it is, and keeps running until the draft is activated in its place.`
          : "The new version starts as a draft, with a small starting process to author from. To carry an existing "
            + "version's process forward instead, draft a copy of that version." }
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
      { !source && (
        <TextField
          label="Description"
          fullWidth
          multiline
          rows={2}
          value={description}
          onChange={event => setDescription(event.target.value)}
        />
      ) }
    </FormDialog>
  );
}

export default NewVersionDialog;
