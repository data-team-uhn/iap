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

import { useState, type ReactNode } from "react";

import CancelIcon from "@mui/icons-material/Cancel";
import {
  Box,
  CircularProgress,
  Step,
  StepIcon,
  type StepIconProps,
  StepLabel,
  Stepper,
  Typography,
  type TypographyProps,
  useMediaQuery,
} from "@mui/material";
import { type Breakpoint, type Theme, useTheme } from "@mui/material/styles";

import { visuallyHidden } from "../visuallyHidden";

// Compact keeps to one line, showing only the step underway and how far along it is.
export type StepProgressLayout = "horizontal" | "vertical" | "compact";

interface StepProgressProps {
  // Labels, in order. The caller decides how many and what they are called.
  steps: readonly string[];
  // Index of the step underway. A value equal to `steps.length` means every step is done.
  activeStep: number;
  // Why the step underway stopped. It is drawn as failed, and the steps before it stay completed.
  // Ignored once every step is done.
  error?: string;
  layout?: StepProgressLayout | Partial<Record<Breakpoint, StepProgressLayout>>;
  // Draw the step underway, and the pulse that leads to it, in the primary colour instead of the
  // secondary accent.
  disableAccent?: boolean;
}

interface StepsProps {
  steps: readonly string[];
  activeStep: number;
  error?: string;
  // The step that just ended, while the next one arrives.
  ended?: number;
}

const DEFAULT_LAYOUT = { xs: "compact", sm: "horizontal" } as const;

const ICON_SIZE = 36;

// How long the move from one step to the next takes, before the next one shows as underway.
const ARRIVAL = "0.7s";

// Secondary, or primary with `disableAccent`, set once on the root.
const ACCENT = "var(--iap-step-accent)";

const classes = {
  ring: "StepProgress-ring",
  ended: "StepProgress-ended",
  arriving: "StepProgress-arriving",
  leaving: "StepProgress-leaving",
  entering: "StepProgress-entering",
} as const;

function useLayout(layout: NonNullable<StepProgressProps["layout"]>): StepProgressLayout {
  const theme = useTheme();
  const options = { noSsr: true };
  const matches: Record<Breakpoint, boolean> = {
    xs: true,
    sm: useMediaQuery(theme.breakpoints.up("sm"), options),
    md: useMediaQuery(theme.breakpoints.up("md"), options),
    lg: useMediaQuery(theme.breakpoints.up("lg"), options),
    xl: useMediaQuery(theme.breakpoints.up("xl"), options),
  };
  if (typeof layout === "string") {
    return layout;
  }
  let chosen: StepProgressLayout = DEFAULT_LAYOUT.xs;
  for (const breakpoint of theme.breakpoints.keys) {
    const value = layout[breakpoint];
    if (value !== undefined && matches[breakpoint]) {
      chosen = value;
    }
  }
  return chosen;
}

// Remembers the step left behind when the bar last moved forward.
function useEndedStep(step: number): number | undefined {
  const [seen, setSeen] = useState<{ step: number; ended?: number }>({ step });
  if (seen.step === step) {
    return seen.ended;
  }
  const ended = step > seen.step ? seen.step : undefined;
  setSeen({ step, ended });
  return ended;
}

function IconSlot({ children }: { children: ReactNode }) {
  return (
    <Box
      sx={{
        position: "relative",
        display: "grid",
        placeItems: "center",
        width: ICON_SIZE,
        height: ICON_SIZE,
        flexShrink: 0,
      }}
    >
      {children}
    </Box>
  );
}

function Ring() {
  return (
    <Box className={classes.ring} aria-hidden sx={{ position: "absolute", inset: 0, display: "flex", color: ACCENT }}>
      <CircularProgress color="inherit" size={ICON_SIZE} sx={{ opacity: 0.5 }} />
    </Box>
  );
}

// A cross in a circle, the counterpart of a completed step's check.
function FailedIcon() {
  return <CancelIcon color="error" />;
}

// The slot stays the same size when the ring goes, so the label does not jump sideways.
function StepProgressIcon({ active, completed, error, ...rest }: StepIconProps) {
  if (error) {
    return <IconSlot><FailedIcon /></IconSlot>;
  }
  return (
    <IconSlot>
      {active ? <Ring /> : null}
      <StepIcon active={active} completed={completed} {...rest} />
    </IconSlot>
  );
}

interface FailureProps {
  label: string | undefined;
  message: string;
  labelHidden?: boolean;
  variant?: TypographyProps["variant"];
}

function Failure({ label = "", message, labelHidden = false, variant = "body2" }: FailureProps) {
  return (
    <Typography role="alert" variant={variant} color="error" sx={{ minWidth: 0 }}>
      <Box component="span" sx={labelHidden ? visuallyHidden : undefined}>{`${label} stopped. `}</Box>
      {message}
    </Typography>
  );
}

function LinearSteps({ steps, activeStep, error, ended, orientation }: StepsProps & {
  orientation: "horizontal" | "vertical";
}) {
  const getStepClass = (index: number) => {
    if (ended === undefined) {
      return undefined;
    }
    if (index === activeStep) {
      return classes.arriving;
    }
    return index === ended ? classes.ended : undefined;
  };
  return (
    <>
      <Stepper activeStep={activeStep} orientation={orientation}>
        {steps.map((label, index) => (
          // Positions identify steps; labels need not be unique.
          <Step key={index} className={getStepClass(index)}>
            <StepLabel
              error={error !== undefined && index === activeStep}
              optional={orientation === "vertical" && error !== undefined && index === activeStep
                ? <Failure label={label} message={error} labelHidden variant="caption" />
                : undefined}
              slots={{ stepIcon: StepProgressIcon }}
            >
              {label}
            </StepLabel>
          </Step>
        ))}
      </Stepper>
      {orientation === "horizontal" && error !== undefined
        ? <Box sx={{ mt: 1 }}><Failure label={steps[activeStep]} message={error} /></Box>
        : null}
    </>
  );
}

interface CompactLineProps {
  icon: ReactNode;
  children: ReactNode;
  className?: string;
  "aria-hidden"?: true;
}

function CompactLine({ icon, children, ...rest }: CompactLineProps) {
  return (
    <Box {...rest} sx={{ display: "flex", alignItems: "center", gap: 1, minWidth: 0 }}>
      <IconSlot>{icon}</IconSlot>
      {children}
    </Box>
  );
}

function CompactLabel({ children }: { children: ReactNode }) {
  return <Typography variant="body2" noWrap sx={{ minWidth: 0 }}>{children}</Typography>;
}

function CompactSteps({ steps, activeStep, error, ended }: StepsProps) {
  const done = activeStep >= steps.length;
  let line: ReactNode;
  if (error !== undefined) {
    line = (
      <CompactLine icon={<FailedIcon />}>
        <Failure label={steps[activeStep]} message={error} />
      </CompactLine>
    );
  } else if (done) {
    line = (
      <CompactLine
        className={ended === undefined ? undefined : classes.ended}
        icon={<StepIcon icon={steps.length} completed />}
      >
        <CompactLabel>{steps.at(-1)}</CompactLabel>
      </CompactLine>
    );
  } else {
    const counter = (
      <Typography aria-hidden variant="caption" sx={{ fontSize: "0.625rem", fontWeight: 500, lineHeight: 1 }}>
        {`${activeStep + 1}/${steps.length}`}
      </Typography>
    );
    line = <CompactLine icon={<><Ring />{counter}</>}><CompactLabel>{steps[activeStep]}</CompactLabel></CompactLine>;
  }
  if (ended === undefined || done) {
    return line;
  }
  return (
    <Box key={activeStep} sx={{ display: "grid", "& > *": { gridArea: "1 / 1" } }}>
      <CompactLine className={classes.leaving} aria-hidden icon={<StepIcon icon={ended + 1} completed />}>
        <CompactLabel>{steps[ended]}</CompactLabel>
      </CompactLine>
      <Box className={classes.entering} sx={{ display: "flex", minWidth: 0 }}>{line}</Box>
    </Box>
  );
}

// A band of the accent colour swept once along a connector, in its direction.
const sweep = (axis: "X" | "Y", easing: string) => ({
  backgroundImage: `linear-gradient(${axis === "X" ? "90deg" : "180deg"}, transparent, ${ACCENT}, transparent)`,
  backgroundSize: axis === "X" ? "40% 100%" : "100% 40%",
  animation: `stepProgressSweep${axis} ${ARRIVAL} ${easing}`,
});

const sweepFrames = (axis: "X" | "Y") => ({
  from: { opacity: 1, [`backgroundPosition${axis}`]: "-70%" },
  to: { opacity: 1, [`backgroundPosition${axis}`]: "170%" },
});

const rootSx = (disableAccent: boolean) => (theme: Theme) => {
  const { palette } = theme.vars ?? theme;
  const fade = `${theme.transitions.duration.shorter}ms`;
  const pop = `${theme.transitions.duration.complex}ms`;
  const { easeIn, easeOut } = theme.transitions.easing;
  return {
    width: "100%",
    "--iap-step-accent": disableAccent ? palette.primary.main : palette.secondary.main,
    "@keyframes stepProgressSweepX": sweepFrames("X"),
    "@keyframes stepProgressSweepY": sweepFrames("Y"),
    "@keyframes stepProgressPop": { "50%": { scale: "1.25" } },
    "@keyframes stepProgressFadeIn": { from: { opacity: 0 } },
    "@keyframes stepProgressFadeOut": { to: { opacity: 0, visibility: "hidden" } },
    "@keyframes stepProgressSettleIcon": { from: { color: palette.text.disabled } },
    "@keyframes stepProgressSettleLabel": { from: { color: palette.text.secondary } },
    "& .MuiStepIcon-root.Mui-active": { color: ACCENT },
    "& .MuiStepIcon-root.Mui-completed": { color: palette.primary.main },
    "& .MuiStepConnector-vertical": { marginLeft: `${ICON_SIZE / 2 - 0.5}px` },
    [`& .${classes.leaving}`]: { display: "none" },
    "@media (prefers-reduced-motion: reduce)": {
      "& .MuiCircularProgress-root, & .MuiCircularProgress-circle": { animation: "none" },
    },
    "@media (prefers-reduced-motion: no-preference)": {
      [`& .${classes.ended} .MuiStepIcon-root, & .${classes.leaving} .MuiStepIcon-root`]: {
        animation: `stepProgressPop ${pop} ${easeOut}`,
      },
      [`& .${classes.arriving}`]: {
        "& .MuiStepConnector-root": { position: "relative" },
        "& .MuiStepConnector-root::after": {
          content: "\"\"",
          position: "absolute",
          opacity: 0,
          backgroundRepeat: "no-repeat",
        },
        "& .MuiStepConnector-horizontal::after": {
          insetInline: 0,
          top: "50%",
          height: 3,
          marginTop: "-1.5px",
          ...sweep("X", easeOut),
        },
        "& .MuiStepConnector-vertical::after": { insetBlock: 0, left: -1, width: 3, ...sweep("Y", easeOut) },
        "& .MuiStepIcon-root": { animation: `stepProgressSettleIcon ${fade} ${easeIn} ${ARRIVAL} backwards` },
        "& .MuiStepLabel-label": { animation: `stepProgressSettleLabel ${fade} ${easeIn} ${ARRIVAL} backwards` },
        [`& .${classes.ring}`]: { animation: `stepProgressFadeIn ${fade} ${easeIn} ${ARRIVAL} backwards` },
      },
      [`& .${classes.leaving}`]: {
        display: "flex",
        animation: `stepProgressFadeOut ${fade} ${easeOut} ${ARRIVAL} forwards`,
      },
      [`& .${classes.entering}`]: { animation: `stepProgressFadeIn ${fade} ${easeIn} ${ARRIVAL} backwards` },
    },
  };
};

// Work moving through named stages. The stage underway wears a faded ring. When a stage ends, it
// takes its check mark, a pulse runs on to the next one, and that one starts once the pulse lands.
function StepProgress({ steps, activeStep, error, layout = DEFAULT_LAYOUT, disableAccent = false }: StepProgressProps) {
  const resolved = useLayout(layout);
  const ended = useEndedStep(activeStep);
  const done = activeStep >= steps.length;
  const failure = done ? undefined : error;
  const props = { steps, activeStep, error: failure, ended: failure === undefined ? ended : undefined };
  const status = done
    ? `All ${steps.length} steps done.`
    : `Step ${activeStep + 1} of ${steps.length}. ${steps[activeStep] ?? ""}.`;
  return (
    <Box sx={rootSx(disableAccent)}>
      <Box role="status" sx={visuallyHidden}>{status}</Box>
      {resolved === "compact" ? <CompactSteps {...props} /> : <LinearSteps {...props} orientation={resolved} />}
    </Box>
  );
}

export default StepProgress;
