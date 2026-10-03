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

import { useEffect, useState } from "react";

import { Button, FormControlLabel, MenuItem, Stack, Switch, TextField } from "@mui/material";

import StepProgress, { type StepProgressLayout } from "@iap/frontend-commons/components/StepProgress";

// How long each step stays underway before the next one starts.
const STEP_MS = 3_000;

const STEPS = [ "Collect", "Review", "Save" ];

const LAYOUTS: Record<string, { label: string; layout?: StepProgressLayout }> = {
  default: { label: "Default" },
  horizontal: { label: "Horizontal", layout: "horizontal" },
  vertical: { label: "Vertical", layout: "vertical" },
  compact: { label: "Compact", layout: "compact" },
};

// A dashboard toy for the stepped progress bar. It walks three steps on a timer, so the ring, the
// move between steps, and a failure can be seen in every layout, with or without the accent, without
// sending a document through the pipeline. Shown on the homepage when the app is started with --test.
function StepProgressWidget() {
  const [activeStep, setActiveStep] = useState(0);
  const [failed, setFailed] = useState(false);
  const [layout, setLayout] = useState("default");
  const [accent, setAccent] = useState(true);
  const done = activeStep >= STEPS.length;

  useEffect(() => {
    if (done || failed) {
      return undefined;
    }
    const timer = setTimeout(() => setActiveStep(step => step + 1), STEP_MS);
    return () => clearTimeout(timer);
  }, [activeStep, done, failed]);

  const replay = () => {
    setFailed(false);
    setActiveStep(0);
  };

  return (
    <Stack spacing={2}>
      <StepProgress
        steps={STEPS}
        activeStep={activeStep}
        error={failed ? "The demo was asked to fail here." : undefined}
        layout={LAYOUTS[layout].layout}
        disableAccent={!accent}
      />
      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap", alignItems: "center" }}>
        <Button variant="outlined" color="error" disabled={done || failed} onClick={() => setFailed(true)}>
          Fail this step
        </Button>
        <Button variant="outlined" disabled={activeStep === 0 && !failed} onClick={replay}>
          Play again
        </Button>
        <TextField
          select
          size="small"
          label="Layout"
          value={layout}
          onChange={event => setLayout(event.target.value)}
          sx={{ minWidth: 140 }}
        >
          {Object.entries(LAYOUTS).map(([key, option]) => <MenuItem key={key} value={key}>{option.label}</MenuItem>)}
        </TextField>
        <FormControlLabel
          control={<Switch checked={accent} onChange={event => setAccent(event.target.checked)} />}
          label="Accent"
        />
      </Stack>
    </Stack>
  );
}

export default StepProgressWidget;
