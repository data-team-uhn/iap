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

import DeleteIcon from "@mui/icons-material/Delete";
import {
  Alert,
  Button,
  CircularProgress,
  DialogActions,
  DialogContent,
  IconButton,
  List,
  ListItem,
  ListItemText,
  Tooltip,
  Typography
} from "@mui/material";

import ErrorDialog from "@iap/frontend-commons/components/ErrorDialog";
import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { isNotAuthenticated, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf, RequestError } from "@iap/frontend-commons/requestFailure";
import { lastSegmentOf } from "@iap/frontend-commons/serializedNode";
import { sendEvent } from "@iap/frontend-commons/workflowEvents";

import { examineDeletion, type DeletionResponse } from "./deletionApi";

interface DeleteItemProps {
  path: string;
  name?: string;
  type?: string;
  variant?: "icon" | "text" | "extended";
  size?: "small" | "medium" | "large";
  label?: string;
  disabled?: boolean;
  onDeleted?: () => void;
  onClose?: () => void;
}

// A delete button for any resource, with its confirmation dialog.
//
// Before the dialog asks for confirmation, it asks the deletion endpoint for a dry run. The dialog
// then says what the deletion would do: how much goes, and what refers to the resource or which guard
// refuses it, either of which blocks it. Confirming sends the resource's workflow a `delete` event,
// which moves it to the archive.
//
// Required props:
// path: the absolute path of the resource to delete
//
// Optional props:
// name: what to call the resource in the dialog. Defaults to the last segment of the path
// type: the kind of thing being deleted, such as "submission", used in the wording
// variant: "icon" (default), "text", or "extended" (icon and text)
// size, disabled: passed to the button
// label: the button's text, and the icon's tooltip. Defaults to "Delete <type>", or "Delete item"
// onDeleted: called once the resource is gone
// onClose: called whenever the dialog closes, deleted or not
//
// Sample usage:
// <DeleteItem path={submission.path} name={submission.title} type="submission"
//   onDeleted={() => reload()} />
//
const DeleteItem = (props: DeleteItemProps) => {
  const {
    path,
    name = lastSegmentOf(path),
    type,
    variant = "icon",
    size = "large",
    label,
    disabled,
    onDeleted,
    onClose
  } = props;

  const authenticatedFetch = useAuthenticatedFetch();

  const [ open, setOpen ] = useState(false);
  const [ impact, setImpact ] = useState<DeletionResponse | null>(null);
  const [ busy, setBusy ] = useState(false);
  const [ error, setError ] = useState<string | null>(null);

  const subject = type ? `${type} "${name}"` : `"${name}"`;
  const buttonText = label ?? `Delete ${type ?? "item"}`;

  // Nothing makes a refused deletion proceed, so the dialog offers nothing but closing
  const vetoes = impact?.vetoes ?? [];
  const vetoed = vetoes.length > 0;
  const referenced = !vetoed
    && ((impact?.referrers ?? []).length > 0 || (impact?.inaccessibleReferrers ?? 0) > 0);
  const blocked = vetoed || referenced;

  const close = () => {
    setOpen(false);
    setImpact(null);
    setBusy(false);
    onClose?.();
  };

  // Closes the confirmation and opens the error dialog in the same render
  const fail = (message: string) => {
    setOpen(false);
    setImpact(null);
    setError(message);
  };

  // The session expired and the user did not sign back in. The server did answer, so this is not
  // reported as a network failure.
  const failNotAuthenticated = () =>
    fail(`You are no longer signed in, so the ${type ?? "item"} was not deleted. Sign in and try again.`);

  const examine = () => {
    setOpen(true);
    setImpact(null);
    setBusy(true);
    examineDeletion(authenticatedFetch, path)
      .then(outcome => setImpact(outcome))
      .catch((err: unknown) => {
        console.error("Could not determine what deleting %s would do", path, err);
        if (isNotAuthenticated(err)) {
          // The deletion would fail the same way, so there is nothing to confirm
          failNotAuthenticated();
          return;
        }
        // Without a dry run, the dialog asks a plain confirmation. The workflow still refuses what
        // it must.
        setImpact(null);
      })
      .finally(() => setBusy(false));
  };

  const confirm = () => {
    setBusy(true);
    sendEvent(authenticatedFetch, path, "delete")
      .then(() => {
        close();
        onDeleted?.();
      })
      .catch((err: unknown) => {
        console.error("Could not delete %s", path, err);
        if (err instanceof RequestError && err.status === 409) {
          // Something changed since the dry run, so the dialog shows the new answer
          examine();
          return;
        }
        setBusy(false);
        fail(`The ${type ?? "item"} could not be deleted. ${messageOf(err)}`);
      });
  };

  const trigger = variant === "icon"
    ? (
      <Tooltip title={buttonText}>
        <span>
          <IconButton onClick={examine} size={size} disabled={disabled} aria-label={buttonText}>
            <DeleteIcon fontSize={size === "small" ? size : undefined} />
          </IconButton>
        </span>
      </Tooltip>
    )
    : (
      <Button
        color="error"
        onClick={examine}
        size={size}
        disabled={disabled}
        startIcon={variant === "extended" ? <DeleteIcon /> : undefined}
      >
        {buttonText}
      </Button>
    );

  return (
    <>
      {trigger}
      <ResponsiveDialog
        open={open}
        onClose={close}
        title={blocked ? `Cannot delete this ${type ?? "item"}` : `Delete this ${type ?? "item"}?`}
      >
        <DialogContent dividers>
          {busy && !impact
            ? <CircularProgress aria-label="Checking what this would delete" />
            : (
              <>
                <Typography gutterBottom>
                  {blocked
                    ? `${subject} cannot be deleted:`
                    : `You are about to delete ${subject}.`}
                </Typography>
                {vetoed && (
                  <List dense>
                    {vetoes.map(veto => (
                      <ListItem key={`${veto.vetoer}:${veto.path}`} disableGutters>
                        <ListItemText primary={veto.reason} secondary={veto.path} />
                      </ListItem>
                    ))}
                  </List>
                )}
                {referenced && <Alert severity="warning">{impact?.["status.message"]}</Alert>}
                {!blocked && <ImpactSummary impact={impact} />}
              </>
            )}
        </DialogContent>
        <DialogActions>
          <Button variant="outlined" onClick={close}>{blocked ? "Close" : "Cancel"}</Button>
          {!blocked && (
            <Button variant="contained" color="error" disabled={busy} onClick={confirm}>
              {busy ? "Deleting…" : "Delete"}
            </Button>
          )}
        </DialogActions>
      </ResponsiveDialog>
      <ErrorDialog open={error !== null} onClose={() => setError(null)}>
        <Typography>{error}</Typography>
      </ErrorDialog>
    </>
  );
};

// What else goes with the resource, and that the deletion can be undone. Says only the latter when
// the deletion takes nothing else.
const ImpactSummary = ({ impact }: { impact: DeletionResponse | null }) => {
  const items = impact?.items?.length ?? 0;
  const links = impact?.removedLinks?.length ?? 0;

  return (
    <>
      {items > 1 && (
        <Typography variant="body2">
          {`This removes ${items} items in total, including everything inside them.`}
        </Typography>
      )}
      {links > 0 && (
        <Typography variant="body2">
          {`${links} link${links > 1 ? "s" : ""} to it will be removed.`}
        </Typography>
      )}
      <Typography variant="body2" sx={{ mt: 1 }}>
        It will be moved to the archive, and can be restored from there.
      </Typography>
    </>
  );
};

export default DeleteItem;
