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

import { type ReactNode, useCallback, useEffect, useRef, useState } from "react";

import {
  Box,
  Button,
  Divider,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useLocation } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import NoticeSnackbar, { type Notice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import TagChip from "@iap/tags/TagChip";

import {
  type Decision,
  type LoggedErrorDetail,
  RESOLUTIONS,
  TRIAGE_CATEGORY,
  acknowledgeError,
  errorNameFromRoute,
  fetchLoggedError,
  resolutionLabel,
  simpleName,
} from "./errorTrackingApi";

/** A timestamp as a reader wants it, or the raw value if it is not one we can parse. */
function moment(value: string | undefined): string {
  if (value === undefined) {
    return "—";
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

// One labelled fact about the error.
function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" component="div">{label}</Typography>
      <Typography variant="body2" component="div">{children}</Typography>
    </Box>
  );
}

// A bounded sample the server kept: the messages, subjects or actors this fault was seen with.
// Nothing is shown at all when there are none, rather than an empty heading.
function Sample({ label, values, hint }: { label: string; values: string[]; hint: string }) {
  if (values.length === 0) {
    return null;
  }
  return (
    <Box>
      <Typography variant="subtitle2">{label}</Typography>
      <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 0.5 }}>
        {hint}
      </Typography>
      <Stack component="ul" sx={{ m: 0, pl: 3 }}>
        {values.map(value => (
          <Typography key={value} component="li" variant="body2" sx={{ wordBreak: "break-word" }}>
            {value}
          </Typography>
        ))}
      </Stack>
    </Box>
  );
}

// Preformatted evidence — a stack trace, or the context of the last occurrence. Scrolls inside its
// own box: a stack trace is wider than any screen, and letting it widen the page would push the
// triage form off the side.
function Preformatted({ label, text }: { label: string; text: string }) {
  return (
    <Box>
      <Typography variant="subtitle2" sx={{ mb: 0.5 }}>{label}</Typography>
      <Box
        component="pre"
        sx={{
          m: 0,
          p: 1.5,
          overflowX: "auto",
          maxHeight: 360,
          overflowY: "auto",
          bgcolor: "action.hover",
          borderRadius: 1,
          fontSize: "0.75rem",
          lineHeight: 1.5,
        }}
      >
        {text}
      </Box>
    </Box>
  );
}

// One decision somebody took, in the order they were taken, newest first.
function DecisionEntry({ decision }: { decision: Decision }) {
  return (
    <Box>
      <Stack direction="row" spacing={1} sx={{ alignItems: "baseline", flexWrap: "wrap" }}>
        <Typography variant="body2" sx={{ fontWeight: "medium" }}>
          {resolutionLabel(decision.resolution)}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {decision.createdBy ?? "somebody"} · {moment(decision.created)}
          {decision.acknowledgedOccurrences !== undefined
            && ` · after ${String(decision.acknowledgedOccurrences)} occurrence(s)`}
        </Typography>
      </Stack>
      {decision.note !== undefined && (
        <Typography variant="body2" color="text.secondary" sx={{ wordBreak: "break-word" }}>
          {decision.note}
        </Typography>
      )}
    </Box>
  );
}

/**
 * The administration console page for one recorded error, at {@code /admin/errors/<fingerprint>}:
 * everything the instance kept about the fault, and the form for recording what was decided.
 *
 * A decision is appended rather than replacing the last one, and the triage markers shown above are
 * *derived* from the decisions server-side — so a successful submission re-reads the error rather
 * than patching what is on screen, which is the only way the markers here can be trusted.
 */
function LoggedErrorView() {
  const { pathname } = useLocation();
  const doFetch = useAuthenticatedFetch();
  // Derived at render, never stored: the React Compiler lint rejects a setState reached
  // synchronously from an effect, and a route that names no error is not a fetch to make
  const name = errorNameFromRoute(pathname);

  const [ error, setError ] = useState<LoggedErrorDetail | null>(null);
  const [ loadError, setLoadError ] = useState<string | null>(null);
  const [ settled, setSettled ] = useState(false);
  const [ reloads, setReloads ] = useState(0);
  const [ notice, setNotice ] = useState<Notice | undefined>(undefined);
  const [ resolution, setResolution ] = useState(RESOLUTIONS[0].name);
  const [ note, setNote ] = useState("");
  const [ saving, setSaving ] = useState(false);

  // Reads are sent in order but can land out of order — a reload after a decision can overtake a
  // retry already in flight — so each one carries a token and only the newest is applied. The same
  // shape the submission editor uses; a `cancelled` flag would not do, because the writes happen
  // inside the read rather than after awaiting it.
  const newestRead = useRef(0);

  // Both the first read and the retry button go through this, so a retry cannot drift from the load
  // it is retrying. It resolves only once the fetch has settled, which is what lets LoadError show
  // the attempt's own progress.
  const load = useCallback((): Promise<void> => {
    if (name === null) {
      return Promise.resolve();
    }
    newestRead.current += 1;
    const token = newestRead.current;
    // Written with callbacks rather than await deliberately: every setState below then sits in a
    // promise callback, which is what keeps react-hooks/set-state-in-effect satisfied when the
    // effect calls this. An async body would put them on the synchronous path as far as the rule
    // is concerned, however many awaits precede them.
    //
    // The outcome is applied in ONE place, so there is a single decision about whether this read is
    // still the one being waited for, rather than the same question asked in three callbacks.
    return fetchLoggedError(doFetch, name)
      .then(result => ({ result, failure: null as string | null }))
      .catch((cause: unknown) => ({
        result: null,
        failure: cause instanceof Error ? cause.message : "The recorded error could not be read",
      }))
      .then(({ result, failure }) => {
        if (token !== newestRead.current) {
          return;
        }
        setError(result);
        setLoadError(failure);
        setSettled(true);
      });
  }, [ doFetch, name ]);

  // Navigating from one error to another must not leave the previous one's details on screen under
  // the new one's heading. Done as a render-phase adjustment rather than in the effect: `load`
  // deliberately sets nothing synchronously, because setState reached synchronously from an effect
  // is what react-hooks/set-state-in-effect rejects — the same shape AnswerField uses to follow a
  // changed value.
  const [ shown, setShown ] = useState(name);
  if (shown !== name) {
    setShown(name);
    setError(null);
    setLoadError(null);
    setSettled(false);
  }

  useEffect(() => {
    void load();
  }, [ load, reloads ]);

  if (name === null) {
    // Not a LoadError: nothing failed to load and retrying cannot help, because the address itself
    // names no single error. Saying so beats rendering an empty page that looks like one.
    return (
      <AdminScreen title="Recorded error">
        <Typography color="text.secondary">
          This address does not name a recorded error.
        </Typography>
      </AdminScreen>
    );
  }

  // A const arrow rather than a function declaration, and declared after the early return, so that
  // `name` is narrowed to a string here and this needs no null guard of its own — there is no form
  // to submit on a page that names no error. A hoisted declaration would not inherit the narrowing.
  const submit = (): void => {
    setSaving(true);
    acknowledgeError(doFetch, name, resolution, note)
      .then(outcome => {
        if (outcome.status === "ok") {
          setNotice({ title: "Decision recorded", severity: "success" });
          setNote("");
          // The triage markers are derived from the decisions when the write commits, so what is on
          // screen is stale the moment this succeeds
          setReloads(previous => previous + 1);
        } else {
          setNotice({
            title: "The decision was not recorded",
            message: outcome.message,
            // Only worth another attempt when the server could not carry it out; a refused
            // resolution or a vanished error would refuse again
            onRetry: outcome.status === "failed" ? submit : undefined,
          });
        }
      })
      .catch(() => {
        setNotice({
          title: "The decision could not be sent",
          message: "The request did not reach the server.",
          onRetry: submit,
        });
      })
      .finally(() => { setSaving(false); });
  };

  // The phrase for a problem; for a failure the throwable's simple name, because the package would
  // fill the heading and the fully-qualified name is shown once below, as the "Thrown" fact
  const heading = error === null
    ? "Recorded error"
    : (error.problem ?? (simpleName(error.type) || error.name));

  return (
    <AdminScreen title={heading}>
      <LoadingOverlay open={!settled} />
      {loadError !== null && (
        <LoadError
          title="The recorded error could not be read"
          message={loadError}
          onRetry={load}
          sx={{ mb: 2 }}
        />
      )}
      {error !== null && (
        <Stack spacing={3}>
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", alignItems: "center" }}>
            <TagChip tags={error.triage} category={TRIAGE_CATEGORY} />
            <Typography variant="body2" color="text.secondary">
              {error.kind === "failure" ? "Something was thrown" : "Nothing was thrown"}
            </Typography>
          </Stack>

          <Box
            sx={{
              display: "grid",
              gap: 2,
              gridTemplateColumns: { xs: "1fr 1fr", sm: "repeat(4, 1fr)" },
            }}
          >
            <Fact label="Component">{error.component ?? "—"}</Fact>
            <Fact label="Operation">{error.operation ?? "—"}</Fact>
            <Fact label="Occurrences">{error.occurrences}</Fact>
            <Fact label="Fingerprint">
              <Box component="span" sx={{ fontFamily: "monospace", wordBreak: "break-all" }}>
                {error.name}
              </Box>
            </Fact>
            <Fact label="First seen">{moment(error.firstSeen)}</Fact>
            <Fact label="Last seen">{moment(error.lastOccurrence)}</Fact>
            {error.type !== undefined && <Fact label="Thrown">{error.type}</Fact>}
          </Box>

          <Sample
            label="Messages"
            values={error.messages}
            hint="A sample of the distinct messages this fault was seen with, most recent first."
          />
          <Sample
            label="Subjects"
            values={error.subjects}
            hint="A sample of what it happened to. Diagnostic evidence, not a work queue."
          />
          <Sample
            label="Acting for"
            values={error.actors}
            hint="A sample of the users it happened on behalf of. Absent for background work."
          />

          {error.lastContext !== undefined
            && <Preformatted label="Context of the last occurrence" text={error.lastContext} />}
          {error.stackTrace !== undefined
            && <Preformatted label="Stack trace" text={error.stackTrace} />}

          <Divider />

          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="subtitle1" sx={{ mb: 0.5 }}>Record a decision</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              Nothing here is ever deleted. A decision is added to the ones below, and if this fault
              happens again it goes back to needing attention on its own.
            </Typography>
            <Stack spacing={2}>
              <TextField
                select
                label="Decision"
                value={resolution}
                onChange={event => { setResolution(event.target.value); }}
                sx={{ maxWidth: 320 }}
              >
                {RESOLUTIONS.map(choice => (
                  <MenuItem key={choice.name} value={choice.name}>
                    {choice.label} — {choice.hint}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label="Why (optional)"
                value={note}
                onChange={event => { setNote(event.target.value); }}
                multiline
                minRows={2}
                helperText="Kept with the decision, for whoever looks at this next."
              />
              <Box>
                <Button variant="contained" onClick={submit} disabled={saving}>
                  Record decision
                </Button>
              </Box>
            </Stack>
          </Paper>

          <Box>
            <Typography variant="subtitle1" sx={{ mb: 1 }}>
              Decisions{error.decisions.length > 0 && ` (${String(error.decisions.length)})`}
            </Typography>
            {error.decisions.length === 0
              ? (
                <Typography variant="body2" color="text.secondary">
                  Nobody has recorded a decision about this yet.
                </Typography>
              )
              : (
                <Stack spacing={1.5}>
                  {error.decisions.map(decision => (
                    <DecisionEntry key={decision.name} decision={decision} />
                  ))}
                </Stack>
              )}
          </Box>
        </Stack>
      )}
      <NoticeSnackbar notice={notice} onClose={() => { setNotice(undefined); }} />
    </AdminScreen>
  );
}

export default LoggedErrorView;
