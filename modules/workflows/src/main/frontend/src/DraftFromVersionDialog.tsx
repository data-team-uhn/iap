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

import { DialogContentText, TextField } from "@mui/material";

import FormDialog from "@iap/frontend-commons/components/FormDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import useNewVersionLabel from "./useNewVersionLabel";
import { versionLabel } from "./workflowModel";
import { draftFromVersion } from "./workflowWrites";

import type { WorkflowSummary, WorkflowVersionSummary } from "./workflowModel";

interface DraftFromVersionDialogProps {
  workflow: WorkflowSummary;
  // The version copied into the new draft
  source: WorkflowVersionSummary;
  onClose: () => void;
  // Called with the new draft's path, for the caller to open it.
  onCreated: (versionPath: string) => void;
}

// Starts a draft holding a copy of one of the workflow's versions.
function DraftFromVersionDialog({ workflow, source, onClose, onCreated }: DraftFromVersionDialogProps) {
  const version = useNewVersionLabel(workflow);
  const fetchUtil = useAuthenticatedFetch();
  const copied = versionLabel(source);

  return (
    <FormDialog
      title={`New draft from version ${copied}`}
      submitLabel="Create draft"
      valid={version.valid}
      onSubmit={() => draftFromVersion(fetchUtil, source.path, version.label).then(onCreated)}
      onClose={onClose}
    >
      <DialogContentText>
        {`The new version starts as a draft holding a copy of version ${copied}. Version ${copied} is left as it `
          + "is, and keeps running until the draft is activated in its place."}
      </DialogContentText>
      <TextField {...version.field} />
    </FormDialog>
  );
}

export default DraftFromVersionDialog;
