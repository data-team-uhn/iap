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

import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Tooltip from "@mui/material/Tooltip";

import CodePill from "@iap/frontend-commons/components/CodePill";

import { describeCardinality } from "../cardinality";
import { useCatalogue } from "../useCatalogue";

import type { CatalogueField } from "../types";

// The small labels a row carries.

const PHI_TOOLTIP =
  "Identifiable information. Asking for it may need approval before the data can be released.";

/** A count, on a collection or a database; `accent` for what has been chosen. */
export function CountPill({ label, tone = "neutral", hideOnMobile = false }: {
  label: string;
  tone?: "neutral" | "accent";
  hideOnMobile?: boolean;
}) {
  return (
    <Chip
      size="small"
      label={label}
      color={tone === "accent" ? "primary" : "default"}
      sx={{ flex: "none", ...(hideOnMobile && { display: { xs: "none", sm: "inline-flex" } }) }}
    />
  );
}

/**
 * Marks a field the catalogue flags as able to identify a person, when the display config shows PHI.
 * A field nobody assessed shows no badge.
 */
export function PhiBadge({ phi }: { phi?: boolean }) {
  const { display } = useCatalogue();
  if (!phi || !display.tree.field.showPhi) {
    return null;
  }
  return (
    // Focusable, with the tooltip as its description rather than its name, so keyboard and screen
    // reader users can reach the reason
    <Tooltip title={PHI_TOOLTIP} describeChild>
      <Chip size="small" variant="outlined" color="error" label="PHI" tabIndex={0} sx={{ flex: "none" }} />
    </Tooltip>
  );
}

/** How many values a field holds, said in words, with the notation kept for the tooltip. */
export function CardinalityBadge({ cardinality }: { cardinality: string }) {
  if (!cardinality) {
    return null;
  }
  const descriptor = describeCardinality(cardinality);
  if (!descriptor) {
    // A notation with no description in words is shown as written, without a tooltip
    return <Chip size="small" label={cardinality} sx={{ flex: "none" }} />;
  }
  return (
    <Tooltip title={descriptor.tip} describeChild>
      <Chip
        size="small"
        label={descriptor.glyph}
        color={descriptor.required ? "primary" : "default"}
        tabIndex={0}
        sx={{ flex: "none" }}
      />
    </Tooltip>
  );
}

/** The source system's own name for a field, when the display config shows it and the label differs. */
export function FieldSourceName({ field }: { field: CatalogueField }) {
  const { display } = useCatalogue();
  if (!display.tree.field.showIdentifier || field.labelIsFallback) {
    return null;
  }
  return <CodePill name={field.identifier} />;
}

/** A source system's own name, hidden on a narrow screen, where the row's label needs the room. */
export function WideScreenCode({ name }: { name: string }) {
  return (
    <Box component="span" sx={{ display: { xs: "none", sm: "inline" }, minWidth: 0 }}>
      <CodePill name={name} />
    </Box>
  );
}
