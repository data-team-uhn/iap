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
  /** Hides the badge on a narrow screen, where the row's name needs the room. */
  hideOnMobile?: boolean;
}

// Theme tokens, so a badge follows the colour scheme. `action.hover` and `action.selected` are
// translucent, and tint whatever surface is beneath.
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
        ...(hideOnMobile
 && { display: { xs: "none", sm: "inline" } }),
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
