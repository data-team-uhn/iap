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

import { useMemo, type ReactNode } from "react";

import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";

import { countPhiFields, groupSelection } from "../selectionGrouping";
import { useCatalogue } from "../useCatalogue";
import { useSelection } from "../useSelection";
import SelectionGroup from "./SelectionGroup";

interface SelectionContentProps {
  /** Collapses the panel, or shuts the sheet, depending on which is holding this. */
  onDismiss: () => void;
  dismissLabel: string;
  dismissIcon: ReactNode;
  /** What a host puts at the foot of the panel — saving, sending, whatever the step needs. */
  actions?: ReactNode;
}

function count(value: number, noun: string): string {
  return `${String(value)} ${value === 1 ? noun : `${noun}s`}`;
}

/**
 * What has been chosen.
 *
 * Rendered by both the panel and the sheet, so the two cannot drift apart: on a wide screen this
 * sits beside the tree, and on a narrow one behind a summary bar, but it is the same thing either
 * way.
 */
export default function SelectionContent({ onDismiss, dismissLabel, dismissIcon, actions }:
SelectionContentProps) {
  const { catalogue, display } = useCatalogue();
  const { selected, count: chosen, clear } = useSelection();
  const groups = useMemo(() => groupSelection(catalogue, selected), [ catalogue, selected ]);
  const phiCount = useMemo(() => countPhiFields(groups), [ groups ]);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      <Box sx={{ px: 2.5, pt: 2.5, pb: 1.5 }}>
        <Box sx={{ display: "flex", alignItems: "center" }}>
          <Typography variant="overline" sx={{ flex: 1 }}>Your selection</Typography>
          <IconButton size="small" onClick={onDismiss} title={dismissLabel} aria-label={dismissLabel}>
            {dismissIcon}
          </IconButton>
        </Box>
        <Typography variant="h4" component="p">{chosen}</Typography>
        <Box sx={{ display: "flex", alignItems: "baseline", gap: 1 }}>
          <Typography variant="caption" component="p" sx={{ flex: 1, color: "text.secondary" }}>
            {chosen === 0
              ? "no fields selected"
              : `${count(chosen, "field")} from ${count(groups.length, "collection")}`}
          </Typography>
          {/* The whole-selection counterpart to each group's "Remove all", worded as one so the two
              read as the same act at two scales. Without it, emptying a large selection means
              working through it collection by collection */}
          {chosen > 0 && (
            <Link
              component="button"
              type="button"
              variant="caption"
              onClick={clear}
              sx={{ color: "text.secondary", "&:hover": { color: "error.main" } }}
            >
              Clear selection
            </Link>
          )}
        </Box>
      </Box>

      {display.tree.field.showPhi && phiCount > 0 && (
        // One line, at every width. "May" is the honest word: the catalogue says a field can
        // identify somebody, which is a reason to check rather than a verdict about this request
        <Alert severity="warning" variant="outlined" sx={{ mx: 2.5, mb: 1.5, py: 0.5 }}>
          <Typography variant="caption">
            <Box component="span" sx={{ fontWeight: "bold" }}>PHI — </Box>
            {`${String(phiCount)} of ${count(chosen, "field")} may need approval before release.`}
          </Typography>
        </Alert>
      )}

      <Box sx={{ flex: 1, minHeight: 0, overflowY: "auto", px: 2.5 }}>
        {chosen === 0
          ? (
            <Box sx={{ py: 5, textAlign: "center" }}>
              <Box
                aria-hidden
                sx={{ width: 38, height: 38, mx: "auto", mb: 1.5, borderRadius: 1,
                  border: "2px dashed", borderColor: "divider" }}
              />
              <Typography variant="subtitle1" component="p" sx={{ mb: 0.5 }}>
                Nothing selected yet
              </Typography>
              <Typography
                variant="caption"
                component="p"
                sx={{ color: "text.secondary", lineHeight: 1.5 }}
              >
                Tick fields in the tree — or a whole collection — and they will collect here.
              </Typography>
            </Box>
          )
          : groups.map(group => (
            <SelectionGroup
              key={`${group.databaseIdentifier}/${group.collectionIdentifier}`}
              group={group}
            />
          ))}
      </Box>

      {actions}
    </Box>
  );
}
