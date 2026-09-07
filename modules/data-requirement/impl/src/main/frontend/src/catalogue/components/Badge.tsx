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

import type { ReactNode } from "react";

import Typography from "@mui/material/Typography";


export type BadgeTone = "neutral" | "accent" | "danger";

export type BadgeShape = "pill" | "chip";

interface BadgeProps {
  children: ReactNode;
  /** Neutral unless it needs emphasis, or is saying something about identifiability. */
  tone?: BadgeTone;
  /** Fully rounded for a count, softly rounded for a label. */
  shape?: BadgeShape;
  /**
   * Drops the badge on a narrow screen, where the row's name has to win.
   *
   * A named prop rather than an `sx` passthrough: this owns the geometry of every small label in
   * the catalogue, and a general escape hatch is how that ownership quietly ends. Hiding is the one
   * thing a caller has needed to say, so it is the one thing it can say.
   */
  hideOnMobile?: boolean;
}

// Written as theme tokens rather than colour values, which is what makes a badge look like the rest
// of whatever surrounds it and follow a dark scheme without being told. `action.hover` and
// `action.selected` are translucent, so they tint the surface they sit on instead of assuming one.
const TONES = {
  neutral: { bgcolor: "action.hover", color: "text.secondary" },
  accent: { bgcolor: "action.selected", color: "primary.main" },
  danger: { bgcolor: "action.hover", color: "error.main", border: 1, borderColor: "error.main" },
} as const;

export default function Badge({ children, tone = "neutral", shape = "chip", hideOnMobile = false }:
BadgeProps) {
  return (
    <Typography
      variant="caption"
      component="span"
      sx={{
        flex: "none",
        whiteSpace: "nowrap",
        fontWeight: "medium",
        // `inline` above the breakpoint because that is what a badge is when nothing sets this, so
        // the prop decides only whether the badge appears and never how it is laid out once it does
        ...(hideOnMobile && { display: { xs: "none", sm: "inline" } }),
        px: shape === "pill" ? 1 : 0.75,
        py: "2px",
        borderRadius: shape === "pill" ? 5 : 1,
        ...TONES[tone],
      }}
    >
      {children}
    </Typography>
  );
}
