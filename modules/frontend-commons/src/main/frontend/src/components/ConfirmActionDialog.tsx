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

import { useState, type ReactNode } from "react";

import { Alert, Button, DialogActions, DialogContent, type ButtonProps } from "@mui/material";

import { messageOf } from "../requestFailure";
import { useAsyncAction } from "../useAsyncAction";
import ResponsiveDialog from "./ResponsiveDialog";

interface ConfirmActionDialogProps {
  title: string;
  // Says what the action does. A failure is reported under it.
  children: ReactNode;
  confirmLabel: string;
  // What the confirm button says while the action runs, such as "Removing…". Defaults to `confirmLabel`.
  workingLabel?: string;
  confirmColor?: ButtonProps["color"];
  // Runs the action. Resolving closes the dialog. Rejecting keeps it open and reports the failure.
  onConfirm: () => Promise<void>;
  // Takes over a rejection: returning true reports nothing, so that the caller can turn a refusal
  // into something else, such as an offer of an alternative.
  interceptFailure?: (error: unknown) => boolean;
  // Whether a rejection is final, such as a refusal. Confirming again would fail the same way, so the
  // confirm button stays disabled. Without it, every failure may be retried.
  isFinal?: (error: unknown) => boolean;
  onClose: () => void;
}

// A dialog that asks before running an action, and reports a failure inside itself. While the
// action runs, the dialog takes no input: not its buttons, its close button or Escape.
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
  {
    title, children, confirmLabel, workingLabel, confirmColor, onConfirm, interceptFailure, isFinal, onClose,
  }: ConfirmActionDialogProps,
) {
  const [ refused, setRefused ] = useState(false);
  const { working, failure, run } = useAsyncAction<string>({
    // A rejection the caller took over is not reported
    onFailure: error => {
      if (interceptFailure?.(error)) {
        return undefined;
      }
      setRefused(isFinal?.(error) ?? false);
      return messageOf(error);
    },
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
        <Button variant="contained" color={confirmColor} onClick={() => run(onConfirm)} disabled={working || refused}
          loading={working}>
          {working ? workingLabel ?? confirmLabel : confirmLabel}
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default ConfirmActionDialog;
