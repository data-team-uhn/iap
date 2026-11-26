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

import { type ReactNode, useState } from "react";

import { Alert, Button, Stack, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { COMPLETE_STEP, UPLOAD_STEP, describeWhyNotNext, getNextStep, getPreviousStep } from "./intake";
import { type SubmissionForm, runMainExtraction, sendDocuments } from "./submissionForm";

// The steps of a request whose documents a model reads, around the requirements of the step shown.
// Next on the uploads sends them to be read, or runs `sendUploads` when given, such as completing the
// workflow step that waits for them. Next on the confirmation reads the rest out of them. With no
// optional document uploaded, Next skips to the rest and sends nothing. Back only moves between the steps.
// `finish` is shown beside Back on the last step, where Next would be.
function IntakeSteps({ path, form, step, reading, sendUploads, onStep, onChanged, finish, children }: {
  path: string;
  form: SubmissionForm;
  step: number;
  reading: boolean;
  sendUploads?: () => Promise<void>;
  onStep: (step: number) => void;
  onChanged: () => void;
  finish?: ReactNode;
  children: ReactNode;
}) {
  const doFetch = useAuthenticatedFetch();
  const [ busy, setBusy ] = useState(false);
  const [ error, setError ] = useState<string>();
  const whyNotNext = describeWhyNotNext(form, step, reading);

  const goOn = () => {
    const next = getNextStep(form, step);
    if (next === COMPLETE_STEP && step === UPLOAD_STEP) {
      onStep(next);
      return;
    }
    setBusy(true);
    setError(undefined);
    const send = sendUploads ?? (() => sendDocuments(doFetch, path));
    const action = step === UPLOAD_STEP ? send() : runMainExtraction(doFetch, path);
    action
      .then(() => {
        onStep(next);
        onChanged();
      }, (e: unknown) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(false));
  };

  return (
    <Stack spacing={2}>
      {children}
      {error && <Alert severity="error">{error}</Alert>}
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", justifyContent: "flex-end" }}>
        { step < COMPLETE_STEP && whyNotNext && <Typography variant="description">{whyNotNext}</Typography> }
        { step > UPLOAD_STEP && (
          <Button disabled={busy} onClick={() => onStep(getPreviousStep(form, step))}>Back</Button>
        ) }
        { step < COMPLETE_STEP && (
          <Button variant="contained" disabled={busy || whyNotNext !== undefined} onClick={goOn}>Next</Button>
        ) }
        { step === COMPLETE_STEP && finish }
      </Stack>
    </Stack>
  );
}

export default IntakeSteps;
