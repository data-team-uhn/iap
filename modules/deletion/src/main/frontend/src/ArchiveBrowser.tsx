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
  Button,
  Link as MuiLink,
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
import { Link as RouterLink } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import {
  entryRoute,
  fetchArchiveEntries,
  purgeEntry,
  restoreEntry,
  type ArchiveEntry,
  type ArchivePage,
} from "./archiveApi";
import { failureMessage } from "./archiveOutcome";
import { PurgeDialog } from "./PurgeDialog";
import { useArchiveAction } from "./useArchiveAction";

/** How long to wait after the last keystroke before asking the server again. */
const FILTER_DELAY_MS = 300;

/** The table's columns. A sortable column's `property` is what the server sorts by. */
const COLUMNS: { property: string; label: string; sortable: boolean }[] = [
  { property: "jcr:created", label: "Deleted at", sortable: true },
  { property: "deletedBy", label: "Deleted by", sortable: true },
  { property: "requestedPath", label: "Deleted path", sortable: true },
  { property: "itemCount", label: "Items", sortable: false },
];

// When the entry was deleted, in the reader's locale, with the ISO-8601 timestamp as a tooltip.
function Deleted({ entry }: { entry: ArchiveEntry }) {
  if (!entry.created) {
    return <>—</>;
  }
  return (
    <Tooltip title={entry.created}>
      <span>{new Date(entry.created).toLocaleString()}</span>
    </Tooltip>
  );
}

// The archive's page: every archive entry, with a restore and a purge for each. Only the purge asks
// for confirmation.
//
// A view on the `iap/coreUI/view` extension point, which the application shell shows at
// /admin/archive. A user who cannot read /Archive gets an error message rather than an empty table.
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

  const [ confirming, setConfirming ] = useState<ArchiveEntry | null>(null);

  const { busy, act } = useArchiveAction({ onDone: () => { setReloadKey(key => key + 1); } });

  // Answers can arrive out of order. Only the latest request's answer is shown.
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
    // Ignores an answer that arrives after unmounting or after a newer request
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

  const rows = data?.rows ?? [];

  return (
    <AdminScreen
      title="Archive"
      description={"Everything that has been deleted and not yet destroyed. Restoring an entry puts every item "
        + "it holds back where it was deleted from; purging it destroys them."}
    >
      <LoadingOverlay open={!settled} />
      {loadError !== null && <Alert severity="error" sx={{ my: 2 }}>{loadError}</Alert>}

      <TextField
        label="Filter by path or user"
        value={filterText}
        onChange={event => { setFilterText(event.target.value); }}
        size="small"
        fullWidth
        sx={{ mb: 2 }}
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
                <TableCell><Deleted entry={entry} /></TableCell>
                <TableCell>{entry.deletedBy}</TableCell>
                <TableCell>
                  <Tooltip title={entry.originalPaths.join(", ")}>
                    {/* To the entry's page, which says whether a restore and a purge would succeed */}
                    <MuiLink component={RouterLink} to={entryRoute(entry.shortPath)}>
                      {entry.requestedPath}
                    </MuiLink>
                  </Tooltip>
                </TableCell>
                <TableCell>{entry.itemCount}</TableCell>
                <TableCell align="right">
                  <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
                    <Button
                      size="small"
                      disabled={busy}
                      onClick={() => { void act(entry.path, restoreEntry); }}
                    >
                      Restore
                    </Button>
                    <Button
                      size="small"
                      color="error"
                      disabled={busy}
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
                  <Typography variant="placeholder">
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

      {/* Mounted only while asking, so that its entry is never null */}
      {confirming !== null && (
        <PurgeDialog
          entry={confirming}
          onPurge={() => act(confirming.path, purgeEntry)}
          onClose={() => { setConfirming(null); }}
        />
      )}

    </AdminScreen>
  );
}

export default ArchiveBrowser;
