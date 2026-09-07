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

import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";


import { describeCardinality } from "../cardinality";
import Badge from "./Badge";

// The small labels a row carries. Each one supplies only tone and meaning: what they look like
// belongs to Badge, so that every label in the tree is the same size and shape as every other.

const PHI_TOOLTIP =
  "Identifiable information — asking for it may need approval before the data can be released.";

/** A count, on a collection or a database. */
export function CountPill({ children, accent = false, hideOnMobile = false }: {
  children: ReactNode;
  accent?: boolean;
  hideOnMobile?: boolean;
}) {
  return (
    <Badge shape="pill" tone={accent ? "accent" : "neutral"} hideOnMobile={hideOnMobile}>
      {children}
    </Badge>
  );
}

/**
 * Says a field can identify somebody.
 *
 * Shown only where the catalogue said so outright. A field nobody assessed carries no badge, which
 * is the same as one assessed and found clear *to look at* — and deliberately so: a badge saying
 * "not assessed" on most of a catalogue would be noise nobody could act on.
 */
export function PhiBadge({ phi }: { phi?: boolean }) {
  if (!phi) {
    return null;
  }
  return (
    <Tooltip title={PHI_TOOLTIP}>
      <Badge tone="danger">PHI</Badge>
    </Tooltip>
  );
}

/** How many values a field holds, said in words, with the notation kept for the tooltip. */
export function CardinalityBadge({ cardinality }: { cardinality: string }) {
  if (!cardinality) {
    return null;
  }
  const descriptor = describeCardinality(cardinality);
  const badge = (
    <Badge tone={descriptor?.required ? "accent" : "neutral"}>
      {descriptor?.glyph ?? cardinality}
    </Badge>
  );
  // A notation this has no words for is shown bare: there is no explanation to offer for it
  return descriptor ? <Tooltip title={descriptor.tip}>{badge}</Tooltip> : badge;
}

/** The source system's own name for something, where a reader has asked to see it. */
export function MonoId({ children, hideOnMobile = false }: {
  children: string;
  hideOnMobile?: boolean;
}) {
  return (
    <Typography
      variant="caption"
      component="span"
      sx={{
        fontFamily: "monospace",
        color: "text.secondary",
        overflow: "hidden",
        textOverflow: "ellipsis",
        whiteSpace: "nowrap",
        ...(hideOnMobile && { display: { xs: "none", sm: "inline" } }),
      }}
    >
      {children}
    </Typography>
  );
}
