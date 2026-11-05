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
import { lastSegmentOf } from "@iap/frontend-commons/serializedNode";

import { requestDeletion, type DeletionResponse } from "./deletionApi";

interface DeleteItemProps {
  path: string;
  name?: string;
  type?: string;
  permanent?: boolean;
  variant?: "icon" | "text" | "extended";
  size?: "small" | "medium" | "large";
  label?: string;
  disabled?: boolean;
  onDeleted?: (outcome: DeletionResponse) => void;
  onClose?: () => void;
}

// A delete button for any resource, with its confirmation dialog.
//
// Before the dialog asks for confirmation, it asks the deletion endpoint for a dry run. The dialog
// then says what the deletion would do: how much goes, what references the resource, and which guard
// refuses it.
//
// Required props:
// path: the absolute path of the resource to delete
//
// Optional props:
// name: what to call the resource in the dialog. Defaults to the last segment of the path
// type: the kind of thing being deleted, such as "submission", used in the wording
// permanent: skip the archive and remove the resource for good. Off by default, and the dialog
//   says the deletion can be undone
// variant: "icon" (default), "text", or "extended" (icon and text)
// size, disabled: passed to the button
// label: the button's text, and the icon's tooltip. Defaults to "Delete <type>", or "Delete item"
// onDeleted: called with the outcome once the resource is gone, including when it was gone already
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
    permanent = false,
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

  // No option makes a vetoed deletion proceed, so the dialog offers none
  const vetoes = impact?.vetoes ?? [];
  const blocked = vetoes.length > 0;
  // Referencing resources can be deleted too, with `recursive`
  const referrers = impact?.referrers ?? [];
  const hiddenReferrers = impact?.inaccessibleReferrers ?? 0;
  const cascades = !blocked && (referrers.length > 0 || hiddenReferrers > 0);

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

  const failWith = (outcome: DeletionResponse) =>
    fail(outcome["status.message"] ?? `The ${type ?? "item"} could not be deleted.`);

  // The session expired and the user did not sign back in. The server did answer, so this is not
  // reported as a network failure.
  const failNotAuthenticated = () =>
    fail(`You are no longer signed in, so the ${type ?? "item"} was not deleted. Sign in and try again.`);

  const examine = () => {
    setOpen(true);
    setImpact(null);
    setBusy(true);
    requestDeletion(authenticatedFetch, path, { permanent, dryRun: true })
      .then(outcome => setImpact(outcome))
      .catch((err: unknown) => {
        console.error("Could not determine what deleting %s would do", path, err);
        if (isNotAuthenticated(err)) {
          // The deletion would fail the same way, so there is nothing to confirm
          failNotAuthenticated();
          return;
        }
        // Without a dry run, the dialog asks a plain confirmation. The deletion endpoint still
        // refuses what it must.
        setImpact(null);
      })
      .finally(() => setBusy(false));
  };

  const confirm = (recursive: boolean) => {
    setBusy(true);
    requestDeletion(authenticatedFetch, path, { permanent, recursive })
      .then(outcome => {
        switch (outcome.status) {
          // "missing" counts as deleted: the resource is gone either way
          case "archived":
          case "deleted":
          case "missing":
            close();
            onDeleted?.(outcome);
            break;
          // Something changed since the dry run, so the dialog shows the new answer
          case "referenced":
          case "vetoed":
            setImpact(outcome);
            break;
          // This call asks for no dry run, but if "dryRun" arrives, nothing was deleted
          case "denied":
          case "invalid":
          case "failed":
          case "dryRun":
            failWith(outcome);
            break;
        }
      })
      .catch((err: unknown) => {
        console.error("Could not delete %s", path, err);
        if (isNotAuthenticated(err)) {
          failNotAuthenticated();
          return;
        }
        fail(`The ${type ?? "item"} could not be deleted. The server could not be reached.`);
      })
      .finally(() => setBusy(false));
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
                {blocked && (
                  <List dense>
                    {vetoes.map(veto => (
                      <ListItem key={`${veto.vetoer}:${veto.path}`} disableGutters>
                        <ListItemText primary={veto.reason} secondary={veto.path} />
                      </ListItem>
                    ))}
                  </List>
                )}
                {cascades && <Alert severity="warning">{impact?.["status.message"]}</Alert>}
                {!blocked && <ImpactSummary impact={impact} permanent={permanent} />}
              </>
            )}
        </DialogContent>
        <DialogActions>
          <Button variant="outlined" onClick={close}>{blocked ? "Close" : "Cancel"}</Button>
          {!blocked && (
            <Button variant="contained" color="error" disabled={busy} onClick={() => confirm(cascades)}>
              {busy ? "Deleting…" : cascades ? "Delete all of them" : "Delete"}
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

// What else goes with the resource, and whether the deletion can be undone. Says only the latter
// when the deletion takes nothing else.
const ImpactSummary = (props: { impact: DeletionResponse | null; permanent: boolean }) => {
  const { impact, permanent } = props;
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
        {permanent
          ? "This cannot be undone."
          : "It will be moved to the archive, and can be restored from there."}
      </Typography>
    </>
  );
};

export default DeleteItem;
