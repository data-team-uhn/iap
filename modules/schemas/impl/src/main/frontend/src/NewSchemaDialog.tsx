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

interface NewSchemaDialogProps {
  onClose: () => void;
  onCreate: (title: string, version: string) => Promise<string | undefined>;
  onCreated: (path: string) => void;
}

// Starting a schema: a title, and a label for its first version, which begins as an empty draft.
function NewSchemaDialog({ onClose, onCreate, onCreated }: NewSchemaDialogProps) {
  const [ title, setTitle ] = useState("");
  const [ version, setVersion ] = useState("1.0");
  const { working, failure, run } = useAsyncAction<string>({ onFailure: messageOf });

  const create = () => run(async () => {
    const path = await onCreate(title.trim(), version.trim());
    if (path) {
      onCreated(path);
    } else {
      onClose();
    }
  });

  return (
    <ResponsiveDialog title="New schema" withCloseButton open onClose={onClose} closeDisabled={working}>
      <DialogContent dividers>
        <Stack spacing={2}>
          <DialogContentText>
            The schema starts with an empty draft version. Nothing can be submitted against it until
            that version is filled in and activated.
          </DialogContentText>
          <TextField
            label="Title"
            required
            value={title}
            disabled={working}
            onChange={event => setTitle(event.target.value)}
            helperText="What submitters and reviewers will call it"
          />
          <TextField
            label="First version"
            required
            value={version}
            disabled={working}
            onChange={event => setVersion(event.target.value)}
          />
          { failure && <Alert severity="error">{failure}</Alert> }
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={working}>Cancel</Button>
        <Button variant="contained" disabled={working || !title.trim() || !version.trim()} onClick={create}>
          Create
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default NewSchemaDialog;
