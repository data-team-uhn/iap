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
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

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

// A delete control for any resource, with the confirmation dialog that belongs to it.
//
// Unlike a plain DELETE button, this asks the server what the deletion would do *before*
// showing the confirmation, so the dialog states the actual consequences — how much goes, what
// refers to it, which guard forbids it — instead of discovering them only after the user has
// already committed to the action. The dry run is what the deletion endpoint's `dryRun` option
// exists for; see docs/deletion.md.
//
// Required props:
// path: the absolute repository path of the resource to delete
//
// Optional props:
// name: what to call the resource in the dialog. Defaults to the last segment of the path
// type: the kind of thing being deleted ("submission", "schema"), used in the wording
// permanent: skip the archive and destroy the resource. Off by default, which means deletions
//   are recoverable and the dialog says so
// variant: "icon" (default), "text", or "extended" (icon and text)
// size, label, disabled, className: passed to the trigger
// onDeleted: called with the successful outcome once the resource is gone. A resource that was
//   already gone counts as deleted, since the caller's goal is met either way
// onClose: called whenever the dialog closes, deleted or not
//
// Sample usage:
// <DeleteItem path={submission.path} name={submission.title} type="submission"
//   onDeleted={() => reload()} />
//
const DeleteItem = (props: DeleteItemProps) => {
  const {
    path,
    name = path.substring(path.lastIndexOf("/") + 1),
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

  // A veto is final: nothing the user can choose here makes the deletion proceed, so the dialog
  // must not offer them an option that would only fail again
  const vetoes = impact?.vetoes ?? [];
  const blocked = vetoes.length > 0;
  // Referrers, on the other hand, are an offer: deleting them too is exactly what `recursive` is
  const referrers = impact?.referrers ?? [];
  const hiddenReferrers = impact?.inaccessibleReferrers ?? 0;
  const cascades = !blocked && (referrers.length > 0 || hiddenReferrers > 0);

  const close = () => {
    setOpen(false);
    setImpact(null);
    setBusy(false);
    onClose?.();
  };

  // Closing the confirmation and opening the error dialog are one render: they are two separate
  // dialogs, so the user sees the explanation, not a silent dismissal
  const fail = (message: string) => {
    setOpen(false);
    setImpact(null);
    setError(message);
  };

  const failWith = (outcome: DeletionResponse) =>
    fail(outcome["status.message"] ?? `The ${type ?? "item"} could not be deleted.`);

  const examine = () => {
    setOpen(true);
    setImpact(null);
    setBusy(true);
    requestDeletion(authenticatedFetch, path, { permanent, dryRun: true })
      .then(outcome => setImpact(outcome))
      .catch((err: unknown) => {
        console.error("Could not determine what deleting %s would do", path, err);
        // Losing the preview is not a reason to block the deletion; the endpoint refuses
        // anything unsafe on its own, so the dialog just falls back to a plain confirmation
        setImpact(null);
      })
      .finally(() => setBusy(false));
  };

  const confirm = (recursive: boolean) => {
    setBusy(true);
    requestDeletion(authenticatedFetch, path, { permanent, recursive })
      .then(outcome => {
        switch (outcome.status) {
          // "missing" counts as done: the caller wanted it absent, and it is
          case "archived":
          case "deleted":
          case "missing":
            close();
            onDeleted?.(outcome);
            break;
          // Something started referring to it, or a guard changed its mind, between the dry run
          // and now — show the new answer rather than the stale one
          case "referenced":
          case "vetoed":
            setImpact(outcome);
            break;
          // "dryRun" cannot arrive here, since this call does not ask for one; if it somehow
          // does, treating it as a failure is right — nothing was deleted
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

// What else goes along with the resource, and whether any of it can be undone. Silent when the
// deletion takes nothing but the resource itself, so the common case stays a plain question.
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
