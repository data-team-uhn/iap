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

import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { useGroupSelection } from "../useGroupSelection";
import { CountPill } from "./badges";
import ExpandCaret from "./ExpandCaret";
import { rowButtonProps } from "./rowButton";
import { INDENT_BY_DEPTH, ROW_END_PADDING } from "./treeLayout";
import TriStateCheckbox from "./TriStateCheckbox";

interface GroupRowProps {
  /** What the row stands for, as a reader is shown it. */
  label: string;
  /** What kind of group it is, such as "database", for the names a screen reader hears. */
  kind: "database" | "collection";
  /** The keys of the fields the group's checkbox stands for. */
  keys: readonly string[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The counts shown after the label. */
  details: ReactNode;
  /** What opens below the row. */
  children: ReactNode;
}

/** A database or a collection: a row that expands and collapses, with a checkbox for everything below it. */
export default function GroupRow({ label, kind, keys, open, onOpenChange, details, children }: GroupRowProps) {
  const { selectedCount, state, toggleAll } = useGroupSelection(keys);
  const isDatabase = kind === "database";

  return (
    <Box>
      <Box
        {...rowButtonProps(() => { onOpenChange(!open); })}
        aria-expanded={open}
        // Without this, the accessible name is the row's whole text, counts included
        aria-label={`${label} ${kind}`}
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 1.25,
          pl: INDENT_BY_DEPTH[isDatabase ? 0 : 1],
          pr: ROW_END_PADDING,
          py: isDatabase ? 1.5 : 1.125,
          cursor: "pointer",
          ...(isDatabase && { borderBottom: 1, borderColor: "divider" }),
          "&:hover": { bgcolor: "action.hover" },
        }}
      >
        <ExpandCaret open={open} size={isDatabase ? 20 : 18} />
        <TriStateCheckbox
          state={state}
          onToggle={toggleAll}
          aria-label={`Select all fields in ${label}`}
        />
        {/* A long name is cut short with an ellipsis rather than pushing the counts off a narrow screen */}
        <Typography
          variant={isDatabase ? "subtitle2" : "body2"}
          component="span"
          noWrap
          sx={{ minWidth: 0, fontWeight: "medium" }}
        >
          {label}
        </Typography>
        {details}
        <Box sx={{ flex: 1 }} />
        {selectedCount > 0 && <CountPill tone="accent" label={`${String(selectedCount)} selected`} />}
      </Box>
      {open && (
        <Box role="group" aria-label={`${label} ${isDatabase ? "collections" : "fields"}`}>
          {children}
        </Box>
      )}
    </Box>
  );
}
