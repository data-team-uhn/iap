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

import CodePill from "@iap/frontend-commons/components/CodePill";

import { CardinalityBadge, FieldSourceName, PhiBadge } from "./badges";
import { INDENT_BY_DEPTH, ROW_END_PADDING } from "./treeLayout";

import type { FieldDisplayConfig } from "../display";
import type { CatalogueField } from "../types";

interface FieldRowProps {
  field: CatalogueField;
  selected: boolean;
  display: FieldDisplayConfig;
  onToggle: (key: string) => void;
}

/**
 * One field, and whether it has been chosen. A click anywhere on the row toggles it, but only the
 * checkbox is focusable, so assistive technology sees one checkbox.
 */
function FieldRow({ field, selected, display, onToggle }: FieldRowProps) {
  return (
    <Box
      onClick={() => { onToggle(field.key); }}
      sx={{
        display: "flex",
        alignItems: "flex-start",
        gap: 1.25,
        pl: INDENT_BY_DEPTH[2],
        pr: ROW_END_PADDING,
        py: 1,
        cursor: "pointer",
        borderLeft: 3,
        borderLeftColor: selected ? "primary.main" : "transparent",
        bgcolor: selected ? "action.selected" : "transparent",
        "&:hover": { bgcolor: selected ? "action.selected" : "action.hover" },
      }}
    >
      <Checkbox
        checked={selected}
        onChange={() => { onToggle(field.key); }}
        onClick={event => { event.stopPropagation(); }}
        slotProps={{ input: { "aria-label": field.label } }}
        sx={{ flex: "none", mt: "-2px" }}
      />
      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.25, flexWrap: "wrap" }}>
          {/* A long name with no spaces, such as an unlabelled identifier, breaks rather than spilling */}
          <Typography variant="body2" component="span" sx={{ fontWeight: "medium", overflowWrap: "anywhere" }}>
            {field.label}
          </Typography>
          <FieldSourceName field={field} />
          {display.showType && field.dataType && <CodePill name={field.dataType} />}
          {display.showCardinality && <CardinalityBadge cardinality={field.cardinality} />}
          <PhiBadge phi={field.phi} />
        </Box>
        {display.showDescription && field.description && (
          <Typography variant="caption" component="p" sx={{ mt: 0.25, overflowWrap: "anywhere" }}>
            {field.description}
          </Typography>
        )}
        {display.showExample && field.example && (
          <Typography variant="code" component="p" sx={{ mt: 0.25, overflowWrap: "anywhere" }}>
            {`e.g. ${field.example}`}
          </Typography>
        )}
      </Box>
    </Box>
  );
}

export default memo(FieldRow);
