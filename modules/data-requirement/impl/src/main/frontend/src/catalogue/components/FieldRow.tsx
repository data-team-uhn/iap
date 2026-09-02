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

import { memo } from "react";

import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import Typography from "@mui/material/Typography";

import { CardinalityBadge, MonoId, PhiBadge } from "./badges";

import type { FieldDisplayConfig } from "../display";
import type { CatalogueField } from "../types";

interface FieldRowProps {
  field: CatalogueField;
  selected: boolean;
  display: FieldDisplayConfig;
  onToggle: (key: string) => void;
  /** Still says whether the field was chosen, but cannot be changed. */
  readOnly?: boolean;
}

/**
 * One field, and whether it has been chosen.
 *
 * The whole row is clickable for convenience, but the checkbox is the control: assistive technology
 * sees one checkbox rather than a button wrapping one, and the row itself is not focusable — which
 * is why it carries no focus ring of its own.
 *
 * Read-only takes the row's own click away as well as disabling the box: a row that still looks
 * pressable and does nothing is worse than one that plainly is not.
 */
function FieldRow({ field, selected, display, onToggle, readOnly = false }: FieldRowProps) {
  return (
    <Box
      onClick={readOnly ? undefined : () => { onToggle(field.key); }}
      sx={{
        display: "flex",
        alignItems: "flex-start",
        gap: 1.25,
        // The wide indent the design asks for eats a third of a phone screen
        pl: { xs: "34px", md: "70px" },
        pr: { xs: 1.5, md: 2.25 },
        py: 1,
        cursor: readOnly ? "default" : "pointer",
        borderLeft: 3,
        borderLeftColor: selected ? "primary.main" : "transparent",
        bgcolor: selected ? "action.selected" : "transparent",
        // Read-only hovers to whatever the row already is, so nothing invites a click that does
        // nothing
        "&:hover": {
          bgcolor: selected ? "action.selected" : (readOnly ? "transparent" : "action.hover"),
        },
      }}
    >
      <Checkbox
        checked={selected}
        disabled={readOnly}
        onChange={() => { onToggle(field.key); }}
        onClick={event => { event.stopPropagation(); }}
        slotProps={{ input: { "aria-label": field.label } }}
        sx={{ flex: "none", mt: "-2px" }}
      />
      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.25, flexWrap: "wrap" }}>
          <Typography variant="body2" component="span" sx={{ fontWeight: "medium" }}>
            {field.label}
          </Typography>
          {/* Repeating the source's own name is pointless where the label was derived from it, so
              it is left off in that case even where a reader asked to see identifiers */}
          {display.showIdentifier && !field.labelIsFallback && <MonoId>{field.identifier}</MonoId>}
          {display.showType && field.dataType && <MonoId>{field.dataType}</MonoId>}
          {display.showCardinality && <CardinalityBadge cardinality={field.cardinality} />}
          {display.showPhi && <PhiBadge phi={field.phi} />}
        </Box>
        {display.showDescription && field.description && (
          <Typography variant="caption" component="p" sx={{ mt: 0.25, color: "text.secondary" }}>
            {field.description}
          </Typography>
        )}
        {display.showExample && field.example && (
          <Typography
            variant="caption"
            component="p"
            sx={{ mt: 0.25, fontFamily: "monospace", color: "text.secondary",
              overflowWrap: "anywhere" }}
          >
            {`e.g. ${field.example}`}
          </Typography>
        )}
      </Box>
    </Box>
  );
}

export default memo(FieldRow);
