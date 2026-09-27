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
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import { type JcrNode, latestVersion, nextVersionLabel, pathOf, titleOf } from "./schemaModel";
import SchemaVersionPicker from "./SchemaVersionPicker";

interface NewSchemaVersionDialogProps {
  schema: JcrNode;
  onClose: () => void;
  onCreate: (label: string, source: string) => Promise<void>;
}

// A new version of a schema: its label, and whether it starts empty or as a copy of one of the schema's
// versions, by default the one made last.
function NewSchemaVersionDialog({ schema, onClose, onCreate }: NewSchemaVersionDialogProps) {
  const [ label, setLabel ] = useState(nextVersionLabel(schema));
  const latest = latestVersion(schema);
  const [ source, setSource ] = useState(latest ? pathOf(latest) : "");
  const { working, failure, run } = useAsyncAction<string>({ onFailure: messageOf });

  return (
    <ResponsiveDialog title={`New version of ${titleOf(schema)}`} withCloseButton open onClose={onClose}
      closeDisabled={working}>
      <DialogContent dividers>
        <Stack spacing={2}>
          <DialogContentText>
            The new version starts as a draft. It can change in any way until it is activated.
          </DialogContentText>
          <TextField
            label="Label"
            required
            value={label}
            disabled={working}
            onChange={event => setLabel(event.target.value)}
          />
          <SchemaVersionPicker schemas={[ schema ]} value={source} onChange={setSource} disabled={working} />
          { failure && <Alert severity="error">{failure}</Alert> }
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={working}>Cancel</Button>
        <Button variant="contained" disabled={working || !label.trim()}
          onClick={() => run(() => onCreate(label.trim(), source))}>
          Create
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default NewSchemaVersionDialog;
