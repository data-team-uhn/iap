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
  DialogActions,
  DialogContent,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";

import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import {
  fetchArchiveEntries,
  purgeEntry,
  restoreEntry,
  type ActionResponse,
  type ArchiveEntry,
  type ArchivePage,
  type AuthenticatedFetch,
} from "./archiveApi";
import { describeOutcome, failureMessage, type Outcome } from "./archiveOutcome";

/** How long to wait after the last keystroke before asking the server again. */
const FILTER_DELAY_MS = 300;

/** The columns that can be ordered on, paired with the JCR property the server knows them by. */
const COLUMNS: { property: string; label: string; sortable: boolean }[] = [
  { property: "jcr:created", label: "Archived", sortable: true },
  { property: "deletedBy", label: "Deleted by", sortable: true },
  { property: "requestedPath", label: "Deleted path", sortable: true },
  { property: "itemCount", label: "Items", sortable: false },
];

// When the entry was archived, in the reader's own locale. The raw timestamp stays available as a
// tooltip, since a support conversation about a deletion is usually conducted in ISO-8601.
function Archived({ entry }: { entry: ArchiveEntry }) {
  if (!entry.created) {
    return <>—</>;
  }
  return (
    <Tooltip title={entry.created}>
      <span>{new Date(entry.created).toLocaleString()}</span>
    </Tooltip>
  );
}

// The archive: every recorded deletion, with what it took with it, and the two things that can be
// done about it. Restoring puts everything back where it came from; purging destroys it for good,
// which is why only that one asks first.
//
// Registered as a view on the `iap/coreUI/view` extension point, so it is displayed by the
// application shell's router at /Archive rather than being a page of its own.
//
// Reachable only by users who can read /Archive — the server decides that, and a refusal surfaces
// here as an ordinary message rather than as an empty table.
export function ArchiveBrowser() {
  const doFetch = useAuthenticatedFetch();

  const [ filterText, setFilterText ] = useState("");
  const [ appliedFilter, setAppliedFilter ] = useState("");
  const [ sortBy, setSortBy ] = useState("jcr:created");
  const [ descending, setDescending ] = useState(true);
  const [ page, setPage ] = useState(0);
  const [ rowsPerPage, setRowsPerPage ] = useState(25);
  const [ reloadKey, setReloadKey ] = useState(0);

  const [ data, setData ] = useState<ArchivePage | null>(null);
  const [ settled, setSettled ] = useState(false);
  const [ loadError, setLoadError ] = useState<string | null>(null);

  const [ notice, setNotice ] = useState<Outcome | null>(null);
  const [ busyPath, setBusyPath ] = useState<string | null>(null);
  const [ confirming, setConfirming ] = useState<ArchiveEntry | null>(null);

  // Requests are sent in order but can land out of order, and an older listing would put back rows
  // a newer one has already replaced.
  const latest = useRef(0);

  useEffect(() => {
    const timer = setTimeout(() => {
      setAppliedFilter(filterText);
      setPage(0);
    }, FILTER_DELAY_MS);
    return () => { clearTimeout(timer); };
  }, [ filterText ]);

  useEffect(() => {
    latest.current += 1;
    const mine = latest.current;
    const current = () => mine === latest.current;
    fetchArchiveEntries(doFetch, {
      offset: page * rowsPerPage,
      limit: rowsPerPage,
      filter: appliedFilter,
      sortBy,
      descending,
    })
      .then(result => { if (current()) { setData(result); setLoadError(null); } })
      .catch((error: unknown) => {
        if (current()) { setLoadError(failureMessage(error, "The archive could not be listed.")); }
      })
      .finally(() => { if (current()) { setSettled(true); } });
    // Retiring the token on the way out covers unmounting as well as being superseded: a response
    // that lands after either one has nothing left to update.
    return () => { latest.current += 1; };
  }, [ doFetch, appliedFilter, sortBy, descending, page, rowsPerPage, reloadKey ]);

  const sortOn = (property: string) => {
    if (sortBy === property) {
      setDescending(!descending);
    } else {
      setSortBy(property);
      setDescending(true);
    }
    setPage(0);
  };

  const act = (entry: ArchiveEntry, action: (f: AuthenticatedFetch, path: string) => Promise<ActionResponse>) => {
    setBusyPath(entry.path);
    action(doFetch, entry.path)
      .then((response: ActionResponse) => {
        setNotice(describeOutcome(response));
        // Only a completed action changes what the table should show; a refusal changed nothing.
        if (response.status === "restored" || response.status === "deleted") {
          setReloadKey(key => key + 1);
        }
      })
      .catch(() => { setNotice({ severity: "error", message: "The request could not be sent." }); })
      .finally(() => { setBusyPath(null); });
  };

  const purgeConfirmed = (entry: ArchiveEntry) => {
    setConfirming(null);
    act(entry, purgeEntry);
  };

  const rows = data?.rows ?? [];

  return (
    <Box>
      <LoadingOverlay open={!settled} />
      <Typography variant="h5" component="h1" gutterBottom>Archive</Typography>
      <Typography variant="body2" color="text.secondary" gutterBottom>
        Everything that has been deleted and not yet destroyed. Restoring an entry puts every item it
        holds back where it was deleted from; purging it destroys them.
      </Typography>

      {notice && (
        <Alert severity={notice.severity} onClose={() => { setNotice(null); }} sx={{ my: 2 }}>
          {notice.message}
        </Alert>
      )}
      {loadError !== null && <Alert severity="error" sx={{ my: 2 }}>{loadError}</Alert>}

      <TextField
        label="Filter by path or user"
        value={filterText}
        onChange={event => { setFilterText(event.target.value); }}
        size="small"
        fullWidth
        sx={{ my: 2 }}
      />

      <TableContainer>
        <Table size="small" aria-label="Archive entries">
          <TableHead>
            <TableRow>
              {COLUMNS.map(column => (
                <TableCell key={column.property}>
                  {column.sortable
                    ? (
                      <TableSortLabel
                        active={sortBy === column.property}
                        direction={descending ? "desc" : "asc"}
                        onClick={() => { sortOn(column.property); }}
                      >
                        {column.label}
                      </TableSortLabel>
                    )
                    : column.label}
                </TableCell>
              ))}
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map(entry => (
              <TableRow key={entry.path} hover>
                <TableCell><Archived entry={entry} /></TableCell>
                <TableCell>{entry.deletedBy}</TableCell>
                <TableCell>
                  <Tooltip title={entry.originalPaths.join(", ")}>
                    <span>{entry.requestedPath}</span>
                  </Tooltip>
                </TableCell>
                <TableCell>{entry.itemCount}</TableCell>
                <TableCell align="right">
                  <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
                    <Button
                      size="small"
                      disabled={busyPath !== null}
                      onClick={() => { act(entry, restoreEntry); }}
                    >
                      Restore
                    </Button>
                    <Button
                      size="small"
                      color="error"
                      disabled={busyPath !== null}
                      onClick={() => { setConfirming(entry); }}
                    >
                      Purge
                    </Button>
                  </Stack>
                </TableCell>
              </TableRow>
            ))}
            {settled && rows.length === 0 && (
              <TableRow>
                <TableCell colSpan={COLUMNS.length + 1}>
                  <Typography variant="body2" color="text.secondary">
                    {appliedFilter ? "No archived deletions match that filter." : "Nothing has been archived yet."}
                  </Typography>
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      <TablePagination
        component="div"
        count={data?.totalrows ?? 0}
        page={page}
        onPageChange={(_event, next) => { setPage(next); }}
        rowsPerPage={rowsPerPage}
        rowsPerPageOptions={[ 10, 25, 50 ]}
        onRowsPerPageChange={event => {
          setRowsPerPage(Number(event.target.value));
          setPage(0);
        }}
      />

      {/* Mounted only while it is being asked, so the entry it is about is a value rather than
          something to keep defending against being absent. */}
      {confirming !== null && (
        <ResponsiveDialog open onClose={() => { setConfirming(null); }} title="Purge this entry?">
          <DialogContent dividers>
            <Typography gutterBottom>
              {`Everything archived under ${confirming.requestedPath} will be destroyed. This cannot be undone.`}
            </Typography>
            <Alert severity="warning">
              {`${String(confirming.itemCount)} archived item(s) will be permanently removed.`}
            </Alert>
          </DialogContent>
          <DialogActions>
            <Button variant="outlined" onClick={() => { setConfirming(null); }}>Cancel</Button>
            <Button variant="contained" color="error" onClick={() => { purgeConfirmed(confirming); }}>
              Purge
            </Button>
          </DialogActions>
        </ResponsiveDialog>
      )}
    </Box>
  );
}

export default ArchiveBrowser;
