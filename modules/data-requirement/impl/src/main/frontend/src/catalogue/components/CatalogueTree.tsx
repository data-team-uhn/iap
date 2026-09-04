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

import DatabaseNode from "./DatabaseNode";
import EmptyState from "./EmptyState";

import type { VisibleDatabase } from "../useCatalogueFilter";
import type { ExpansionApi } from "../useExpansion";

interface CatalogueTreeProps {
  visible: VisibleDatabase[];
  expansion: ExpansionApi;
  query: string;
  /** Whether every database has been excluded, which is a different nothing from a search that
   * found nothing. */
  noDatabasesIncluded: boolean;
  onClearQuery: () => void;
  onIncludeAllDatabases: () => void;
}

/** What there is to choose from. */
export default function CatalogueTree({ visible, expansion, query, noDatabasesIncluded,
  onClearQuery, onIncludeAllDatabases }: CatalogueTreeProps) {
  const searching = query.trim().length > 0;

  return (
    <Paper
      variant="outlined"
      sx={{
        flex: 1,
        minHeight: 0,
        overflow: "auto",
        // The scrollbar's width is held whether or not it is showing, so rows do not jump sideways
        // as a search narrows what is left
        scrollbarGutter: "stable",
      }}
    >
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
              body="Try a shorter term, or search the source's own name for the field instead."
              // Not "Clear filter", which is what the toolbar's button is called: two controls on
              // one screen answering to the same name is a maze for anyone hearing them read out
              actionLabel={searching ? "Show all fields" : undefined}
              onAction={searching ? onClearQuery : undefined}
            />
          )
          : (
            // Deliberately not `role="tree"`. That role obliges every row to be a `treeitem` with
            // the whole arrow-key navigation contract, and these rows are buttons and checkboxes
            // inside groups — announcing a tree with no items in it is worse than announcing none.
            //
            // `group` rather than no role at all, because an `aria-label` on a roleless element is
            // ignored and the name would do nothing. It also matches the two levels below, which
            // are already labelled groups.
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
