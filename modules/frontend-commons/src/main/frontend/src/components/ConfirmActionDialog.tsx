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

import { type ReactNode } from "react";

import { Alert, Button, DialogActions, DialogContent, type ButtonProps } from "@mui/material";

import { messageOf } from "../requestFailure";
import { useAsyncAction } from "../useAsyncAction";
import ResponsiveDialog from "./ResponsiveDialog";

interface ConfirmActionDialogProps {
  title: string;
  // Explains what the action does; the failure report, if any, appears underneath.
  children: ReactNode;
  confirmLabel: string;
  confirmColor?: ButtonProps["color"];
  // Performs the action. Resolving closes the dialog; rejecting keeps it open and reports the
  // failure in place, next to the explanation of what was being attempted.
  onConfirm: () => Promise<void>;
  // Claims a rejection the caller would rather handle itself: returning true suppresses the
  // report, letting the caller turn a particular refusal into something other than an error - an
  // offer of an alternative, say.
  interceptFailure?: (error: unknown) => boolean;
  onClose: () => void;
}

// The shell for an action that asks before it acts: it states the consequences, runs the action
// once, and - since the dialog is already the user's focus - reports a failure right there rather
// than handing it to a second dialog. While the action is in flight the dialog takes no further
// instruction at all - not the buttons, and not the close button or Escape either, since an action
// that cannot be taken back should not be abandoned halfway by a stray keypress.
//
// Sample usage:
// <ConfirmActionDialog
//   title="Delete this draft?"
//   confirmLabel="Delete"
//   confirmColor="error"
//   onConfirm={() => remove(draft)}
//   onClose={close}
//  >
//    <DialogContentText>This cannot be undone.</DialogContentText>
// </ConfirmActionDialog>
//
function ConfirmActionDialog(
  { title, children, confirmLabel, confirmColor, onConfirm, interceptFailure, onClose }: ConfirmActionDialogProps,
) {
  const { working, failure, run } = useAsyncAction<string>({
    // A claimed rejection is discarded here because the caller has taken it over - turned it into an
    // offer of an alternative, say - and reporting it as a failure as well would contradict that
    onFailure: error => interceptFailure?.(error) ? undefined : messageOf(error),
    onSuccess: onClose,
  });

  return (
    <ResponsiveDialog open title={title} width="xs" withCloseButton closeDisabled={working} onClose={onClose}>
      <DialogContent dividers>
        { children }
        { failure && <Alert severity="error" sx={{ mt: 2 }}>{failure}</Alert> }
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={working}>Cancel</Button>
        <Button variant="contained" color={confirmColor} onClick={() => run(onConfirm)} disabled={working}>
          {confirmLabel}
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default ConfirmActionDialog;
