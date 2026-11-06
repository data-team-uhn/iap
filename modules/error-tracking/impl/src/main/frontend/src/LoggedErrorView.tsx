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
import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
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

/** A timestamp in the browser's locale, the raw value if it cannot be parsed, or a dash if absent. */
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
      <Typography variant="caption" component="div">{label}</Typography>
      <Typography variant="body2" component="div">{children}</Typography>
    </Box>
  );
}

// A sample the server kept: the messages, subjects or users of the fault. It renders nothing when the
// sample is empty.
function Sample({ label, values, hint }: { label: string; values: string[]; hint: string }) {
  if (values.length === 0) {
    return null;
  }
  return (
    <Box>
      <Typography variant="subtitle2">{label}</Typography>
      <Typography variant="caption" component="div" sx={{ mb: 0.5 }}>
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

// Preformatted text, such as a stack trace. It scrolls inside its own box, so that a long line does
// not widen the page.
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

// One decision somebody took about the error.
function DecisionEntry({ decision }: { decision: Decision }) {
  return (
    <Box>
      <Stack direction="row" spacing={1} sx={{ alignItems: "baseline", flexWrap: "wrap" }}>
        <Typography variant="body2" sx={{ fontWeight: "medium" }}>
          {resolutionLabel(decision.resolution)}
        </Typography>
        <Typography variant="caption">
          {decision.createdBy ?? "somebody"} · {moment(decision.created)}
          {decision.acknowledgedOccurrences !== undefined
            && ` · after ${String(decision.acknowledgedOccurrences)} occurrence(s)`}
        </Typography>
      </Stack>
      {decision.note !== undefined && (
        <Typography variant="description" sx={{ wordBreak: "break-word" }}>
          {decision.note}
        </Typography>
      )}
    </Box>
  );
}

/**
 * The administration console page for one recorded error, at `/admin/errors/<fingerprint>`: what
 * the instance kept about the fault, and the form for recording a decision.
 *
 * The server computes the triage tags from the decisions, so after recording one the page reads the
 * error again.
 */
function LoggedErrorView() {
  const { pathname } = useLocation();
  const doFetch = useAuthenticatedFetch();
  // Derived at render, not stored: react-hooks/set-state-in-effect rejects a setState reached
  // synchronously from an effect
  const name = errorNameFromRoute(pathname);

  const [ error, setError ] = useState<LoggedErrorDetail | null>(null);
  const [ loadError, setLoadError ] = useState<string | null>(null);
  const [ settled, setSettled ] = useState(false);
  const [ reloads, setReloads ] = useState(0);
  const notify = useNotice();
  const [ resolution, setResolution ] = useState(RESOLUTIONS[0].name);
  const [ note, setNote ] = useState("");
  const [ saving, setSaving ] = useState(false);

  // Reads can land out of order: a re-read after a decision can overtake a retry in flight. Each read
  // carries a token, and only the newest is applied.
  const newestRead = useRef(0);

  // Reads the error, for the first load and for the retry button. It resolves once the fetch has
  // settled, so that LoadError can show the retry's progress.
  const load = useCallback((): Promise<void> => {
    if (name === null) {
      return Promise.resolve();
    }
    newestRead.current += 1;
    const token = newestRead.current;
    // Callbacks, not await: react-hooks/set-state-in-effect counts a setState in an async function
    // as synchronous, however many awaits precede it
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

  // Moving to another error clears the previous one's details. Done during render, since the effect
  // may not set state synchronously.
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
    // Not a LoadError: nothing failed to load, and retrying cannot help
    return (
      <AdminScreen title="Recorded error">
        <Typography variant="placeholder">
          This address does not name a recorded error.
        </Typography>
      </AdminScreen>
    );
  }

  // A const arrow after the early return, where `name` is narrowed to a string. A hoisted function
  // declaration would not see the narrowing.
  const submit = (): void => {
    setSaving(true);
    acknowledgeError(doFetch, name, resolution, note)
      .then(outcome => {
        if (outcome.status === "ok") {
          notify({ title: "Decision recorded", severity: "success" });
          setNote("");
          // The triage tags were computed again when the decision was committed
          setReloads(previous => previous + 1);
        } else {
          notify({
            title: "The decision was not recorded",
            message: outcome.message,
            // A refused resolution or a missing error would be refused again
            onRetry: outcome.status === "failed" ? submit : undefined,
          });
        }
      })
      .catch(() => {
        notify({
          title: "The decision could not be sent",
          message: "The request did not reach the server.",
          onRetry: submit,
        });
      })
      .finally(() => { setSaving(false); });
  };

  // The name of a problem, or the simple class name of what was thrown; the "Thrown" fact below
  // shows the fully qualified name
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
            <Typography variant="description">
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
            <Typography variant="description" sx={{ mb: 2 }}>
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
                <Typography variant="placeholder">
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
    </AdminScreen>
  );
}

export default LoggedErrorView;
