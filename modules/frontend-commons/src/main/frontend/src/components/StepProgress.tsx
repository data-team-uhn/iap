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

// Why the active step stopped.
export interface StepProgressError {
  message: string;
}

// Compact keeps to one line, showing only the step underway and how far along it is.
export type StepProgressLayout = "horizontal" | "vertical" | "compact";

export type StepProgressColor = "primary" | "secondary" | "info" | "success" | "warning";

interface StepProgressProps {
  // Labels, in order. The caller decides how many and what they are called.
  steps: readonly string[];
  // Index of the step underway. A value equal to `steps.length` means every step is done.
  activeStep: number;
  // When set, `activeStep` is drawn as the one that failed, and the steps before it stay completed.
  error?: StepProgressError;
  layout?: StepProgressLayout | Partial<Record<Breakpoint, StepProgressLayout>>;
  // The palette colour of the step underway and of the pulse that leads to it.
  color?: StepProgressColor;
}

interface StepsProps {
  steps: readonly string[];
  shown: number;
  error?: StepProgressError;
  // The step that just ended, while the next one arrives.
  ended?: number;
}

const DEFAULT_LAYOUT = { xs: "compact", sm: "horizontal" } as const;

const ICON_SIZE = 36;

// How long the move from one step to the next takes, before the next one shows as underway.
const ARRIVAL = "0.7s";
const FADE = "0.2s";

// The `color` prop, set once on the root.
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
function useEndedStep(shown: number): number | undefined {
  const [seen, setSeen] = useState<{ shown: number; ended?: number }>({ shown });
  if (seen.shown === shown) {
    return seen.ended;
  }
  const ended = shown > seen.shown ? seen.shown : undefined;
  setSeen({ shown, ended });
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

// The slot stays the same size when the ring goes, so the label does not jump sideways.
function StepProgressIcon({ active, completed, error, ...rest }: StepIconProps) {
  return (
    <IconSlot>
      {active && !error ? <Ring /> : null}
      <StepIcon active={active} completed={completed} error={error} {...rest} />
    </IconSlot>
  );
}

interface FailureProps {
  steps: readonly string[];
  step: number;
  error: StepProgressError;
  labelHidden?: boolean;
  variant?: TypographyProps["variant"];
}

function Failure({ steps, step, error, labelHidden = false, variant = "body2" }: FailureProps) {
  return (
    <Typography role="alert" variant={variant} color="error" sx={{ minWidth: 0 }}>
      <Box component="span" sx={labelHidden ? visuallyHidden : undefined}>{`${steps[step] ?? ""} stopped. `}</Box>
      {error.message}
    </Typography>
  );
}

function LinearSteps({ steps, shown, error, ended, orientation }: StepsProps & {
  orientation: "horizontal" | "vertical";
}) {
  const stepClass = (index: number) => {
    if (ended === undefined) {
      return undefined;
    }
    if (index === shown) {
      return classes.arriving;
    }
    return index === ended ? classes.ended : undefined;
  };
  return (
    <>
      <Stepper activeStep={shown} orientation={orientation}>
        {steps.map((label, index) => (
          // Positions identify steps; labels need not be unique.
          <Step key={index} className={stepClass(index)}>
            <StepLabel
              error={error !== undefined && index === shown}
              optional={orientation === "vertical" && error !== undefined && index === shown
                ? <Failure steps={steps} step={shown} error={error} labelHidden variant="caption" />
                : undefined}
              slots={{ stepIcon: StepProgressIcon }}
            >
              {label}
            </StepLabel>
          </Step>
        ))}
      </Stepper>
      {orientation === "horizontal" && error !== undefined
        ? <Box sx={{ mt: 1 }}><Failure steps={steps} step={shown} error={error} /></Box>
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

function CompactSteps({ steps, shown, error, ended }: StepsProps) {
  const done = shown >= steps.length;
  let line: ReactNode;
  if (error !== undefined) {
    line = (
      <CompactLine icon={<StepIcon icon={shown + 1} error />}>
        <Failure steps={steps} step={shown} error={error} />
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
        {`${shown + 1}/${steps.length}`}
      </Typography>
    );
    line = <CompactLine icon={<><Ring />{counter}</>}><CompactLabel>{steps[shown]}</CompactLabel></CompactLine>;
  }
  if (ended === undefined || done) {
    return line;
  }
  return (
    <Box key={shown} sx={{ display: "grid", "& > *": { gridArea: "1 / 1" } }}>
      <CompactLine className={classes.leaving} aria-hidden icon={<StepIcon icon={ended + 1} completed />}>
        <CompactLabel>{steps[ended]}</CompactLabel>
      </CompactLine>
      <Box className={classes.entering} sx={{ display: "flex", minWidth: 0 }}>{line}</Box>
    </Box>
  );
}

// A band of the accent colour swept once along a connector, in its direction.
const sweep = (axis: "X" | "Y") => ({
  backgroundImage: `linear-gradient(${axis === "X" ? "90deg" : "180deg"}, transparent, ${ACCENT}, transparent)`,
  backgroundSize: axis === "X" ? "40% 100%" : "100% 40%",
  animation: `stepProgressSweep${axis} ${ARRIVAL} ease-out`,
});

const sweepFrames = (axis: "X" | "Y") => ({
  from: { opacity: 1, [`backgroundPosition${axis}`]: "-70%" },
  to: { opacity: 1, [`backgroundPosition${axis}`]: "170%" },
});

const rootSx = (color: StepProgressColor) => (theme: Theme) => {
  const { palette } = theme.vars ?? theme;
  return {
    width: "100%",
    "--iap-step-accent": palette[color].main,
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
        animation: "stepProgressPop 0.35s ease-out",
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
          ...sweep("X"),
        },
        "& .MuiStepConnector-vertical::after": { insetBlock: 0, left: -1, width: 3, ...sweep("Y") },
        "& .MuiStepIcon-root": { animation: `stepProgressSettleIcon ${FADE} ease-in ${ARRIVAL} backwards` },
        "& .MuiStepLabel-label": { animation: `stepProgressSettleLabel ${FADE} ease-in ${ARRIVAL} backwards` },
        [`& .${classes.ring}`]: { animation: `stepProgressFadeIn ${FADE} ease-in ${ARRIVAL} backwards` },
      },
      [`& .${classes.leaving}`]: {
        display: "flex",
        animation: `stepProgressFadeOut ${FADE} ease-out ${ARRIVAL} forwards`,
      },
      [`& .${classes.entering}`]: { animation: `stepProgressFadeIn ${FADE} ease-in ${ARRIVAL} backwards` },
    },
  };
};

// Work moving through named stages. The stage underway wears a faded ring. When a stage ends, it
// takes its check mark, a pulse runs on to the next one, and that one starts once the pulse lands.
function StepProgress({ steps, activeStep, error, layout = DEFAULT_LAYOUT, color = "secondary" }: StepProgressProps) {
  const resolved = useLayout(layout);
  const shown = activeStep;
  const ended = useEndedStep(shown);
  const props = { steps, shown, error, ended: error === undefined ? ended : undefined };
  const status = shown >= steps.length
    ? `All ${steps.length} steps done.`
    : `Step ${shown + 1} of ${steps.length}. ${steps[shown] ?? ""}.`;
  return (
    <Box sx={rootSx(color)}>
      <Box role="status" sx={visuallyHidden}>{status}</Box>
      {resolved === "compact" ? <CompactSteps {...props} /> : <LinearSteps {...props} orientation={resolved} />}
    </Box>
  );
}

export default StepProgress;
