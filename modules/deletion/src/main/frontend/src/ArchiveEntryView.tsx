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

import { useEffect, useRef, useState } from "react";

import {
  Alert,
  Box,
  Button,
  Chip,
  DialogActions,
  DialogContent,
  Divider,
  Link,
  List,
  ListItem,
  ListItemText,
  Stack,
  Typography,
} from "@mui/material";
import { Link as RouterLink, useLocation, useNavigate } from "react-router";

import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import {
  fetchArchiveEntry,
  purgeEntry,
  restoreEntry,
  type ActionResponse,
  type ArchiveEntryDetail,
  type AuthenticatedFetch,
} from "./archiveApi";
import { describeOutcome, failureMessage, type Outcome } from "./archiveOutcome";

/** Where the archive listing lives, for the way back and for after an entry stops existing. */
const ARCHIVE_URL = "/Archive";

/** What each refused restore means, in words an administrator can act on. */
const CONFLICT_REASONS: Record<string, string> = {
  PARENT_MISSING: "the folder it was deleted from no longer exists",
  OCCUPIED: "something else is at that path now",
  NO_RIGHTS: "you may not create it there",
};

// One archived subtree: where it would go back to, and whether it can.
function Item({ path, conflict }: { path: string; conflict?: string }) {
  return (
    <ListItem disableGutters divider>
      <ListItemText
        primary={path}
        secondary={conflict === undefined
          ? "Would be restored here"
          : `Cannot be restored: ${CONFLICT_REASONS[conflict] ?? conflict}`}
      />
      <Chip
        size="small"
        label={conflict ?? "Restorable"}
        color={conflict === undefined ? "success" : "warning"}
        variant="outlined"
      />
    </ListItem>
  );
}

// One archive entry: what it holds, and — the reason this page exists — whether restoring or
// purging it would actually work, asked before anybody commits to either.
//
// Registered as a view on `iap/coreUI/view` for /Archive/*, so the shell's router displays it at the
// entry's own repository path. That path also covers the prefix-tree buckets, which are not entries;
// the endpoint says so and the page reports it rather than rendering an empty entry.
export function ArchiveEntryView() {
  const doFetch = useAuthenticatedFetch();
  const path = useLocation().pathname;
  const navigate = useNavigate();

  const [ entry, setEntry ] = useState<ArchiveEntryDetail | null>(null);
  const [ settled, setSettled ] = useState(false);
  const [ loadError, setLoadError ] = useState<string | null>(null);
  const [ notice, setNotice ] = useState<Outcome | null>(null);
  const [ busy, setBusy ] = useState(false);
  const [ confirming, setConfirming ] = useState(false);
  const [ reloadKey, setReloadKey ] = useState(0);

  const latest = useRef(0);

  useEffect(() => {
    latest.current += 1;
    const mine = latest.current;
    const current = () => mine === latest.current;
    fetchArchiveEntry(doFetch, path)
      .then(result => { if (current()) { setEntry(result); setLoadError(null); } })
      .catch((error: unknown) => {
        if (current()) { setLoadError(failureMessage(error, "That archive entry could not be read.")); }
      })
      .finally(() => { if (current()) { setSettled(true); } });
    return () => { latest.current += 1; };
  }, [ doFetch, path, reloadKey ]);

  const act = (action: (f: AuthenticatedFetch, target: string) => Promise<ActionResponse>) => {
    setBusy(true);
    action(doFetch, path)
      .then((response: ActionResponse) => {
        if (response.status === "restored" || response.status === "deleted") {
          // The entry no longer exists, so there is nothing left for this page to show
          void navigate(ARCHIVE_URL);
          return;
        }
        setNotice(describeOutcome(response));
        // The preflight said this would work, and it did not: something changed underneath, so
        // re-read it rather than leaving a claim on screen that has just been disproved.
        setReloadKey(key => key + 1);
      })
      .catch(() => { setNotice({ severity: "error", message: "The request could not be sent." }); })
      .finally(() => { setBusy(false); });
  };

  const conflictFor = (originalPath: string) =>
    entry?.restoreConflicts.find(conflict => conflict.originalPath === originalPath)?.reason;

  return (
    <Box>
      <LoadingOverlay open={!settled} />
      <Link component={RouterLink} to={ARCHIVE_URL} variant="body2">← Back to the archive</Link>

      {notice && (
        <Alert severity={notice.severity} onClose={() => { setNotice(null); }} sx={{ my: 2 }}>
          {notice.message}
        </Alert>
      )}
      {loadError !== null && <Alert severity="error" sx={{ my: 2 }}>{loadError}</Alert>}

      {entry && (
        <>
          <Typography variant="h5" component="h1" sx={{ mt: 1 }}>{entry.requestedPath}</Typography>
          <Typography variant="body2" color="text.secondary" gutterBottom>
            {`Deleted by ${entry.deletedBy}${entry.created ? ` on ${new Date(entry.created).toLocaleString()}` : ""}`}
          </Typography>

          <Typography variant="h6" component="h2" sx={{ mt: 3 }}>
            {`Archived items (${String(entry.itemCount)})`}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            A restore is all or nothing: every item below has to be placeable before any of them moves.
          </Typography>
          <List dense>
            {entry.originalPaths.map(original => (
              <Item key={original} path={original} conflict={conflictFor(original)} />
            ))}
          </List>

          {!entry.purgeable && (
            <Alert severity="info" sx={{ my: 2 }}>
              <Typography variant="body2">This entry cannot be purged:</Typography>
              <List dense disablePadding>
                {entry.purgeVetoes.map(veto => (
                  <ListItem key={`${veto.vetoer}:${veto.path}`} disableGutters>
                    <ListItemText primary={veto.reason} secondary={veto.path} />
                  </ListItem>
                ))}
              </List>
            </Alert>
          )}

          <Divider sx={{ my: 2 }} />
          <Stack direction="row" spacing={1}>
            <Button
              variant="contained"
              disabled={busy || !entry.restorable}
              onClick={() => { act(restoreEntry); }}
            >
              Restore everything
            </Button>
            <Button
              variant="outlined"
              color="error"
              disabled={busy || !entry.purgeable}
              onClick={() => { setConfirming(true); }}
            >
              Purge
            </Button>
          </Stack>
        </>
      )}

      {confirming && entry && (
        <ResponsiveDialog open onClose={() => { setConfirming(false); }} title="Purge this entry?">
          <DialogContent dividers>
            <Typography gutterBottom>
              {`Everything archived under ${entry.requestedPath} will be destroyed. This cannot be undone.`}
            </Typography>
            <Alert severity="warning">
              {`${String(entry.itemCount)} archived item(s) will be permanently removed.`}
            </Alert>
          </DialogContent>
          <DialogActions>
            <Button variant="outlined" onClick={() => { setConfirming(false); }}>Cancel</Button>
            <Button
              variant="contained"
              color="error"
              onClick={() => { setConfirming(false); act(purgeEntry); }}
            >
              Purge
            </Button>
          </DialogActions>
        </ResponsiveDialog>
      )}
    </Box>
  );
}

export default ArchiveEntryView;
