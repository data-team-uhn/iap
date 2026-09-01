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

import { useLayoutEffect, useMemo, useRef, type ReactNode, type Ref } from "react";

import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";

import { countOf } from "@iap/frontend-commons/words";

import { countPhiFields, groupSelection, unofferedKeys } from "../selectionGrouping";
import { useCatalogue } from "../useCatalogue";
import { useSelection } from "../useSelection";
import SelectionGroup, { UnofferedGroup, type RemoveFields } from "./SelectionGroup";

interface SelectionContentProps {
  /** Collapses the panel, or shuts the drawer, depending on which is holding this. */
  onDismiss: () => void;
  dismissLabel: string;
  dismissIcon: ReactNode;
  /** Takes the dismiss button, for a holder that moves focus to it. */
  dismissRef?: Ref<HTMLButtonElement>;
}

/** What has been chosen. Drawn in the side panel on a wide layout, and in the drawer on a narrow one. */
export default function SelectionContent({ onDismiss, dismissLabel, dismissIcon, dismissRef }:
SelectionContentProps) {
  const { catalogue } = useCatalogue();
  const { selected, deselectFields, clear } = useSelection();
  const groups = useMemo(() => groupSelection(catalogue, selected), [ catalogue, selected ]);
  const unoffered = useMemo(() => unofferedKeys(catalogue, selected), [ catalogue, selected ]);
  const phiCount = useMemo(() => countPhiFields(groups), [ groups ]);
  const chosen = selected.size;

  // A removal takes away the control that was pressed. Focus goes to whichever control now stands in
  // its place, or to the heading once nothing is left to remove.
  const list = useRef<HTMLDivElement>(null);
  const heading = useRef<HTMLSpanElement>(null);
  const refocusAt = useRef<number | null>(null);
  const removeControls = () => [ ...list.current?.querySelectorAll<HTMLElement>("[data-remove]") ?? [] ];
  useLayoutEffect(() => {
    if (refocusAt.current === null) {
      return;
    }
    const controls = removeControls();
    (controls.at(Math.min(refocusAt.current, controls.length - 1)) ?? heading.current)?.focus();
    refocusAt.current = null;
  }, [ selected ]);
  const remove: RemoveFields = (keys, control) => {
    refocusAt.current = Math.max(0, removeControls().indexOf(control));
    deselectFields(keys);
  };
  const clearAll = () => {
    refocusAt.current = 0;
    clear();
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      <Box sx={{ px: 2.5, pt: 2.5, pb: 1.5 }}>
        <Box sx={{ display: "flex", alignItems: "center" }}>
          <Typography ref={heading} variant="overline" tabIndex={-1} sx={{ flex: 1 }}>Your selection</Typography>
          <IconButton ref={dismissRef} size="small" onClick={onDismiss} title={dismissLabel} aria-label={dismissLabel}>
            {dismissIcon}
          </IconButton>
        </Box>
        <Typography variant="h4" component="p">{chosen}</Typography>
        <Box sx={{ display: "flex", alignItems: "baseline", gap: 1 }}>
          <Typography variant="caption" component="p" sx={{ flex: 1 }}>
            {chosen === 0
              ? "no fields selected"
              : `${countOf(chosen, "field")} from ${countOf(groups.length, "collection")}`}
          </Typography>
          {chosen > 0 && (
            <Link
              component="button"
              type="button"
              variant="caption"
              onClick={clearAll}
              sx={{ color: "text.secondary", "&:hover, &:focus-visible": { color: "error.main" } }}
            >
              Clear selection
            </Link>
          )}
        </Box>
      </Box>
      {/* Whatever the tree shows of each field: asking for identifiable data may need approval */}
      {phiCount > 0 && (
        <Alert severity="warning" variant="outlined" sx={{ mx: 2.5, mb: 1.5, py: 0.5 }}>
          <Typography variant="caption">
            <Box component="span" sx={{ fontWeight: "bold" }}>PHI: </Box>
            {`${String(phiCount)} of ${countOf(chosen, "field")} may need approval before release.`}
          </Typography>
        </Alert>
      )}
      <Box ref={list} sx={{ flex: 1, minHeight: 0, overflowY: "auto", px: 2.5 }}>
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
              <Typography variant="placeholder" component="p">
                Tick fields in the tree, or a whole collection, and they will collect here.
              </Typography>
            </Box>
          )
          : (
            <>
              {groups.map(group => (
                <SelectionGroup
                  key={`${group.databaseIdentifier}/${group.collectionIdentifier}`}
                  group={group}
                  onRemove={remove}
                />
              ))}
              {unoffered.length > 0 && <UnofferedGroup keys={unoffered} onRemove={remove} />}
            </>
          )}
      </Box>
    </Box>
  );
}
