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
import Paper from "@mui/material/Paper";
import { visuallyHidden } from "@mui/utils";

import { countOf } from "@iap/frontend-commons/words";

import DatabaseNode from "./DatabaseNode";
import EmptyState from "./EmptyState";

import type { VisibleDatabase } from "../useCatalogueFilter";
import type { ExpansionApi } from "../useExpansion";

interface CatalogueTreeProps {
  visible: VisibleDatabase[];
  expansion: ExpansionApi;
  /** The search as typed, for the empty state to quote. */
  query: string;
  /** Whether a search term is narrowing what is shown. */
  searching: boolean;
  /** Whether every database has been excluded, which has its own empty state. */
  noDatabasesIncluded: boolean;
  onClearQuery: () => void;
  onIncludeAllDatabases: () => void;
}

/** What there is to choose from. */
export default function CatalogueTree({ visible, expansion, query, searching, noDatabasesIncluded,
  onClearQuery, onIncludeAllDatabases }: CatalogueTreeProps) {
  const matches = visible.reduce((total, entry) => total + entry.shownFieldCount, 0);

  return (
    <Paper
      variant="outlined"
      sx={{
        flex: 1,
        minHeight: 0,
        overflow: "auto",
        // Reserves the scrollbar's width, so rows do not shift as a search narrows them
        scrollbarGutter: "stable",
      }}
    >
      {/* Says what a search found, which a screen reader otherwise never hears while typing */}
      <Box role="status" sx={visuallyHidden}>
        {searching ? `${countOf(matches, "field")} match` : ""}
      </Box>
      {noDatabasesIncluded
        ? (
          <EmptyState
            title="No databases included"
            body="Pick at least one source to browse its collections."
            actionLabel="Include all databases"
            onAction={onIncludeAllDatabases}
          />
        )
        : visible.length === 0
          ? (
            <EmptyState
              title={searching ? `No fields match “${query.trim()}”` : "No fields to show"}
              body={searching
                ? "Try a shorter term, or search the source's own name for the field instead."
                : "This catalogue has no fields to choose from."}
              // Not "Clear filter": the toolbar's button already has that name
              actionLabel={searching ? "Show all fields" : undefined}
              onAction={searching ? onClearQuery : undefined}
            />
          )
          : (
            // `group`, not `tree`: a tree requires `treeitem` rows with arrow-key navigation, which
            // these rows lack. A role is still needed, or the `aria-label` is ignored.
            <Box role="group" aria-label="Data catalogue">
              {visible.map(entry => (
                <DatabaseNode
                  key={entry.database.identifier}
                  entry={entry}
                  expansion={expansion}
                  searching={searching}
                />
              ))}
            </Box>
          )}
    </Paper>
  );
}
