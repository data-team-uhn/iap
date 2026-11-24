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

import type { ReactNode } from "react";

import { Alert, Button, DialogActions, DialogContent, Stack, type Breakpoint } from "@mui/material";

import { messageOf } from "../requestFailure";
import { useAsyncAction } from "../useAsyncAction";
import ResponsiveDialog from "./ResponsiveDialog";

interface FormDialogProps {
  title: ReactNode;
  width?: Breakpoint;
  submitLabel: string;
  // What the submit button says while the request runs, such as "Creating…". Defaults to `submitLabel`.
  workingLabel?: string;
  valid: boolean;
  // Sends the request. Resolving closes the dialog; rejecting keeps it open and reports the failure.
  onSubmit: () => Promise<unknown>;
  onClose: () => void;
  // The inputs that gather what `onSubmit` sends, such as a name field.
  children: ReactNode;
}

// A dialog of fields with Cancel and a submit button. The caller owns the fields and the request, and this owns
// what surrounds them: the layout, the in-flight state, the failure report and the way out. While the request runs
// the dialog takes no further instruction, the close button and Escape included.
//
// Sample usage:
// <FormDialog title="New thing" submitLabel="Create" valid={name !== ""} onSubmit={() => create(name)} onClose={close}>
//   <TextField label="Name" value={name} onChange={event => setName(event.target.value)} />
// </FormDialog>
//
function FormDialog(props: FormDialogProps) {
  const { title, width = "sm", submitLabel, workingLabel, valid, onSubmit, onClose, children } = props;
  const { working, failure, run } = useAsyncAction<string>({ onFailure: messageOf, onSuccess: onClose });

  return (
    <ResponsiveDialog open title={title} width={width} withCloseButton closeDisabled={working} onClose={onClose}>
      <DialogContent dividers>
        <Stack spacing={2} sx={{ pt: 1 }}>
          { children }
          { failure && <Alert severity="error">{failure}</Alert> }
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={working}>Cancel</Button>
        <Button variant="contained" onClick={() => run(onSubmit)} disabled={working || !valid} loading={working}>
          {working ? workingLabel ?? submitLabel : submitLabel}
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default FormDialog;
