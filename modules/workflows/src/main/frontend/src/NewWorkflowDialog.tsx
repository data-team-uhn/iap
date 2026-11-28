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

import { MenuItem, TextField } from "@mui/material";

import FormDialog from "@iap/frontend-commons/components/FormDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { createWorkflow } from "./workflowWrites";

import type { WorkflowHomepage } from "./workflowModel";

interface NewWorkflowDialogProps {
  // The homepages a workflow may be created in; the picker is only shown when there is more than one
  // to choose between.
  homepages: WorkflowHomepage[];
  // Which homepage to default to. If not provided, defaults to the first discovered.
  preselected?: string;
  onClose: () => void;
  // Called with the new workflow's path once it exists, for the caller to open it.
  onCreated: (workflowPath: string) => void;
}

// Creating a workflow: its title, an optional description of what the first version does, and that
// version's label. The diagram is not asked for — the workflow is created with a small starting
// diagram and the editor opens on it, which is where a diagram is authored.
//
// There is deliberately no "active" choice here either: a new workflow's first version is a draft,
// and promoting it is a separate, explicit decision made from the workflow's own page.
function NewWorkflowDialog({ homepages, preselected, onClose, onCreated }: NewWorkflowDialogProps) {
  const [ title, setTitle ] = useState("");
  const [ description, setDescription ] = useState("");
  const [ version, setVersion ] = useState("1.0");
  const firstDiscovered = homepages[0]?.path ?? "";
  const [ homepage, setHomepage ] = useState(preselected ?? firstDiscovered);
  const fetchUtil = useAuthenticatedFetch();

  const valid = title.trim() !== "" && version.trim() !== "" && homepage !== "";

  const save = (): Promise<void> =>
    createWorkflow(fetchUtil, {
      homepage,
      title: title.trim(),
      version: version.trim(),
      description: description.trim(),
    }).then(onCreated);

  return (
    <FormDialog title="New workflow" submitLabel="Create" valid={valid} onSubmit={save} onClose={onClose}>
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
    </FormDialog>
  );
}

export default NewWorkflowDialog;
