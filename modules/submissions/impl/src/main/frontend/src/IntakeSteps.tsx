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

import { Alert, Button, Stack, Step, StepLabel, Stepper, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { COMPLETE_STEP, STEP_LABELS, UPLOAD_STEP, describeWhyNotNext } from "./intake";
import { type SubmissionForm, runMainExtraction, sendDocuments } from "./submissionForm";

// The steps of a request whose documents a model reads, around the requirements of the step shown.
// Next on the uploads sends them to be read; Next on the confirmation reads the rest out of them.
// Back only moves between the steps.
function IntakeSteps({ path, form, step, reading, onStep, onChanged, children }: {
  path: string;
  form: SubmissionForm;
  step: number;
  reading: boolean;
  onStep: (step: number) => void;
  onChanged: () => void;
  children: ReactNode;
}) {
  const doFetch = useAuthenticatedFetch();
  const [ busy, setBusy ] = useState(false);
  const [ error, setError ] = useState<string>();
  const whyNotNext = describeWhyNotNext(form, step, reading);

  const goOn = () => {
    setBusy(true);
    setError(undefined);
    const action = step === UPLOAD_STEP ? sendDocuments(doFetch, path) : runMainExtraction(doFetch, path);
    action
      .then(() => {
        onStep(step + 1);
        onChanged();
      }, (e: unknown) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(false));
  };

  return (
    <Stack spacing={2}>
      <Stepper activeStep={step}>
        { STEP_LABELS.map(label => (
          <Step key={label}>
            <StepLabel>{label}</StepLabel>
          </Step>
        )) }
      </Stepper>
      {children}
      {error && <Alert severity="error">{error}</Alert>}
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", justifyContent: "flex-end" }}>
        { step < COMPLETE_STEP && whyNotNext && <Typography variant="description">{whyNotNext}</Typography> }
        { step > UPLOAD_STEP && <Button disabled={busy} onClick={() => onStep(step - 1)}>Back</Button> }
        { step < COMPLETE_STEP && (
          <Button variant="contained" disabled={busy || whyNotNext !== undefined} onClick={goOn}>Next</Button>
        ) }
      </Stack>
    </Stack>
  );
}

export default IntakeSteps;
