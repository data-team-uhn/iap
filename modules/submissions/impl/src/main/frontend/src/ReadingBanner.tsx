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

import { Alert, Box, Button, Stack } from "@mui/material";

import StepProgress from "@iap/frontend-commons/components/StepProgress";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { useNode } from "@iap/frontend-commons/useNode";

import { PHASE_LABEL, READING_PHASES, getFailedPhase, getPhase } from "./readingProgress";
import {
  type ExtractionState,
  type Requirement,
  type SubmissionForm,
  describeBlock,
  readAgain,
  stopProcessing,
} from "./submissionForm";

const STEPS = READING_PHASES.map(phase => PHASE_LABEL[phase]);

// How often the step shown is worked out again while the model is asked, which reports nothing
const PHASE_TICK_MILLISECONDS = 5000;

function asForm(node: Record<string, unknown>): SubmissionForm {
  const requirements = Array.isArray(node.requirements) ? node.requirements as Requirement[] : [];
  return { ...node, requirements } as SubmissionForm;
}

// How long a reading job has held the reading, counted from when the page first saw it take it
function useSinceReading(reading: boolean): number {
  const [ elapsed, setElapsed ] = useState(0);
  useEffect(() => {
    if (!reading) {
      return undefined;
    }
    const start = Date.now();
    const tick = () => setElapsed(Date.now() - start);
    const first = setTimeout(tick, 0);
    const timer = setInterval(tick, PHASE_TICK_MILLISECONDS);
    return () => {
      clearTimeout(first);
      clearInterval(timer);
    };
  }, [ reading ]);
  return reading ? elapsed : 0;
}

// Where reading the uploaded documents stands, and what the submitter can do about it: stop it while
// it runs, read again once it ended. Shown only for a request whose schema reads its documents.
function ReadingBanner({ path, reloads, onChanged }: {
  path: string;
  // Changes whenever the page reads the request again, so this reads the form again with it
  reloads: number;
  onChanged: () => void;
}) {
  const { value: form, reload } = useNode(path, "form", asForm);
  const doFetch = useAuthenticatedFetch();
  const [ error, setError ] = useState<string>();
  const [ busy, setBusy ] = useState(false);
  const readFor = useRef(reloads);
  useEffect(() => {
    if (readFor.current !== reloads) {
      readFor.current = reloads;
      void reload();
    }
  }, [ reloads, reload ]);

  const extraction: ExtractionState | undefined = form?.extraction;
  const running = extraction?.status === "running";
  const sinceReading = useSinceReading(running && extraction.reading === true);
  useEffect(() => {
    // The parse's own reason names the daemon and its paths, so it goes to the console, not the page
    if (extraction?.detail) {
      console.info("Why the document could not be parsed:", extraction.detail);
    }
  }, [ extraction?.detail ]);

  if (!form?.readsDocuments || !extraction) {
    return null;
  }

  const act = (action: Promise<void>) => {
    setBusy(true);
    setError(undefined);
    action
      .then(onChanged, (e: unknown) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(false));
  };
  const canAct = form.editable && !busy;
  const block = describeBlock(extraction);

  if (running) {
    return (
      <Stack spacing={1}>
        <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <StepProgress steps={STEPS} activeStep={READING_PHASES.indexOf(getPhase(extraction, sinceReading))} />
          </Box>
          {form.editable && (
            <Button size="small" disabled={!canAct} onClick={() => act(stopProcessing(doFetch, path))}>
              Stop
            </Button>
          )}
        </Stack>
        {error && <Alert severity="error">{error}</Alert>}
      </Stack>
    );
  }

  const failedPhase = getFailedPhase(extraction);
  // A document the model is sure is the wrong one gets the same answer again, so only a new request helps
  const again = form.editable && extraction.blocked !== true && (
    <Button
      size="small"
      color="inherit"
      disabled={!canAct}
      onClick={() => act(readAgain(doFetch, path, extraction.retryable === true))}
    >
      {extraction.status === "failed" ? "Try again" : "Read again"}
    </Button>
  );
  return (
    <Stack spacing={1}>
      {failedPhase && (
        <StepProgress
          steps={STEPS}
          activeStep={READING_PHASES.indexOf(failedPhase)}
          error={extraction.message ?? "The document could not be read"}
        />
      )}
      {block
        ? <Alert severity="warning" action={again}>{block}</Alert>
        : (
          <Alert severity={extraction.status === "failed" ? "error" : "info"} action={again}>
            {extraction.message
              ?? (extraction.status === "failed"
                ? "The document could not be read."
                : "Answers were read from your document. Check each one before you send the request.")}
          </Alert>
        )}
      {error && <Alert severity="error">{error}</Alert>}
    </Stack>
  );
}

export default ReadingBanner;
