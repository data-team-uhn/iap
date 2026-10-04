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
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import { type EditableField, fieldsOf, type JcrNode } from "./schemaModel";

// The fields this dialog can edit: text ones, which is all a schema or a version has to offer for now
export const editableText = (node: JcrNode): EditableField[] => fieldsOf(node).filter(field => field.kind === "text");

const valueOf = (node: JcrNode, field: EditableField): string => {
  const value = node[field.name];
  return typeof value === "string" ? value : "";
};

interface DetailsDialogProps {
  title: string;
  // The schema or version being edited, serialized with the fields its update would change
  node: JcrNode;
  onClose: () => void;
  // Given only the fields that changed; an emptied optional field is null, which removes it
  onSave: (changes: Record<string, string | null>) => Promise<unknown>;
}

// Edits the text fields a schema or a version describes as editable, sending only what changed.
function DetailsDialog({ title, node, onClose, onSave }: DetailsDialogProps) {
  const fields = editableText(node);
  const [ values, setValues ] = useState<Record<string, string>>(
    Object.fromEntries(fields.map(field => [ field.name, valueOf(node, field) ])));
  const { working, failure, run } = useAsyncAction<string>({ onFailure: messageOf, onSuccess: onClose });

  const changes = Object.fromEntries(fields
    .filter(field => values[field.name].trim() !== valueOf(node, field).trim())
    .map(field => [ field.name, values[field.name].trim() || null ]));
  const missing = fields.some(field => field.mandatory && !values[field.name].trim());

  return (
    <ResponsiveDialog title={title} withCloseButton open onClose={onClose} closeDisabled={working}>
      <DialogContent dividers>
        <Stack spacing={2}>
          { fields.map(field => (
            <TextField
              key={field.name}
              label={field.label}
              value={values[field.name]}
              required={field.mandatory}
              multiline={field.multiline}
              minRows={field.multiline ? 2 : undefined}
              disabled={working}
              onChange={event => setValues(current => ({ ...current, [field.name]: event.target.value }))}
            />
          )) }
          { failure && <Alert severity="error">{failure}</Alert> }
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={working}>Cancel</Button>
        <Button
          variant="contained"
          disabled={working || missing || Object.keys(changes).length === 0}
          onClick={() => run(() => onSave(changes))}
        >
          Save
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default DetailsDialog;
