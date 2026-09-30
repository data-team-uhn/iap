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

import { useEffect, useRef, useState, type ReactNode } from "react";

import {
  Box,
  CircularProgress,
  Step,
  StepIcon,
  type StepIconProps,
  StepLabel,
  Stepper,
  Typography,
} from "@mui/material";

// A step that stopped, and the words to hang under it.
export interface StepProgressError {
  step: number;
  message: string;
}

interface StepProgressProps {
  // Labels, in order. The caller decides how many and what they are called.
  steps: readonly string[];
  // Index of the step underway. A value equal to `steps.length` means every step is done.
  activeStep: number;
  // When set, that step is drawn as the one that failed, and the others up to it stay completed.
  error?: StepProgressError;
}

// A ring around the step that is underway. The slot stays the same size when the ring goes, so the
// label does not jump sideways.
function StepProgressIcon(props: StepIconProps) {
  const underway = props.active === true && props.error !== true && props.completed !== true;
  return (
    <Box sx={{ position: "relative", display: "grid", placeItems: "center", width: 36, height: 36 }}>
      {underway
        ? (
          <CircularProgress
            color="secondary"
            size={36}
            sx={{ position: "absolute", opacity: 0.5 }}
          />
        )
        : null}
      <StepIcon {...props} />
    </Box>
  );
}

// One flash along the line that was just crossed, when a step ends and the next one starts.
function useConnectorPulse(activeStep: number): boolean {
  const [play, setPlay] = useState(false);
  const seen = useRef(activeStep);
  useEffect(() => {
    if (seen.current === activeStep || activeStep <= 0) {
      seen.current = activeStep;
      return undefined;
    }
    seen.current = activeStep;
    setPlay(true);
    const timer = setTimeout(() => setPlay(false), 700);
    return () => clearTimeout(timer);
  }, [activeStep]);
  return play;
}

// A linear stepper for work that moves through named stages. The stage underway wears a faded
// ring. Crossing into the next stage flashes once along the line just crossed. A failed stage
// hangs its message under the label, out of the flow, so the row does not shift.
function StepProgress({ steps, activeStep, error }: StepProgressProps) {
  const shown = error === undefined ? activeStep : error.step;
  const pulse = useConnectorPulse(shown);
  return (
    <Box role={error === undefined ? "status" : undefined} sx={{ width: "100%" }}>
      <Stepper
        activeStep={shown}
        sx={{
          "@keyframes stepConnectorPulse": {
            from: { backgroundPositionX: "-40%" },
            to: { backgroundPositionX: "140%" },
          },
          "& .MuiStepConnector-line": { minWidth: 96 },
          "& .MuiStepIcon-root.Mui-active": { color: "secondary.main" },
          "& .MuiStepIcon-root.Mui-completed": { color: "primary.main" },
          // The line between the step that just ended and the one that just began.
          ...(pulse
            ? {
              "& .MuiStepConnector-root.Mui-active .MuiStepConnector-line": {
                borderTopColor: "transparent",
                height: 3,
                borderRadius: 2,
                backgroundImage: "linear-gradient(90deg, transparent, var(--mui-palette-secondary-main), transparent)",
                backgroundSize: "40% 100%",
                backgroundRepeat: "no-repeat",
                animation: "stepConnectorPulse 0.7s ease-out 1",
              },
            }
            : {}),
          // The failure caption hangs under the label. Kept out of the flow so the step does not shift.
          "& .MuiStepLabel-labelContainer": { position: "relative" },
        }}
      >
        {steps.map((label, index) => {
          const stepProps: { completed?: boolean } = {};
          const labelProps: { optional?: ReactNode; error?: boolean } = {};
          if (error?.step === index) {
            labelProps.error = true;
            labelProps.optional = (
              <Typography
                variant="caption"
                color="error"
                sx={{ position: "absolute", top: "100%", left: 0, width: "max-content", maxWidth: 240 }}
              >
                {error.message}
              </Typography>
            );
            stepProps.completed = false;
          }
          return (
            <Step key={label} {...stepProps}>
              <StepLabel {...labelProps} slots={{ stepIcon: StepProgressIcon }}>{label}</StepLabel>
            </Step>
          );
        })}
      </Stepper>
    </Box>
  );
}

export default StepProgress;
