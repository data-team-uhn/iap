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

import { useMemo } from "react";

import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { useCatalogue } from "../useCatalogue";
import { useGroupSelection } from "../useGroupSelection";
import { useSelection } from "../useSelection";
import { CountPill, MonoId } from "./badges";
import ExpandCaret from "./ExpandCaret";
import FieldRow from "./FieldRow";
import { rowButtonProps } from "./rowButton";
import TriStateCheckbox from "./TriStateCheckbox";

import type { VisibleCollection } from "../useCatalogueFilter";

interface CollectionNodeProps {
  collection: VisibleCollection;
  open: boolean;
  onToggleOpen: (key: string) => void;
  /** Whether a search is narrowing the fields below. */
  searching: boolean;
}

/** One collection, and the fields under it. */
export default function CollectionNode({ collection, open, onToggleOpen, searching }:
CollectionNodeProps) {
  const { display } = useCatalogue();
  const { isSelected, toggleField, readOnly } = useSelection();
  // Only the fields that survived the search, so the checkbox stands for what can actually be seen
  const visibleKeys = useMemo(() => collection.fields.map(field => field.key), [ collection.fields ]);
  const { selectedCount, state, setAll } = useGroupSelection(visibleKeys);

  return (
    <Box>
      <Box
        {...rowButtonProps(() => { onToggleOpen(collection.key); })}
        aria-expanded={open}
        // Named outright: without this the accessible name is the whole row's text, counts and all,
        // which makes the control hard to tell from its neighbours
        aria-label={`${collection.label} collection`}
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 1.25,
          pl: { xs: "14px", md: "42px" },
          pr: { xs: 1.5, md: 2.25 },
          py: 1.125,
          cursor: "pointer",
          "&:hover": { bgcolor: "action.hover" },
        }}
      >
        <ExpandCaret open={open} size={18} />
        <TriStateCheckbox
          state={state}
          onChange={setAll}
          disabled={readOnly}
          aria-label={`Select all fields in ${collection.label}`}
        />
        {/* A long name gives way rather than pushing the count off a narrow screen */}
        <Typography
          variant="body2"
          component="span"
          sx={{ minWidth: 0, fontWeight: "medium", overflow: "hidden", textOverflow: "ellipsis",
            whiteSpace: "nowrap" }}
        >
          {collection.label}
        </Typography>
        {display.tree.collection.showIdentifier && <MonoId hideOnMobile>{collection.identifier}</MonoId>}
        <CountPill>
          {searching
            ? `${String(collection.fields.length)} of ${String(collection.totalFieldCount)}`
            : String(collection.totalFieldCount)}
        </CountPill>
        <Box sx={{ flex: 1 }} />
        {selectedCount > 0 && (
          <Typography
            variant="caption"
            component="span"
            sx={{ color: "primary.main", whiteSpace: "nowrap" }}
          >
            {`${String(selectedCount)} of ${String(visibleKeys.length)}`}
            <Box component="span" sx={{ display: { xs: "none", sm: "inline" } }}> selected</Box>
          </Typography>
        )}
      </Box>
      {open && (
        <Box role="group" aria-label={`${collection.label} fields`}>
          {collection.fields.map(field => (
            <FieldRow
              key={field.key}
              field={field}
              selected={isSelected(field.key)}
              display={display.tree.field}
              onToggle={toggleField}
              readOnly={readOnly}
            />
          ))}
        </Box>
      )}
    </Box>
  );
}
