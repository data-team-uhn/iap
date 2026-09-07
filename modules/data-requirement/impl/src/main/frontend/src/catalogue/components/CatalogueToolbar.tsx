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

import ClearIcon from "@mui/icons-material/Clear";
import SearchIcon from "@mui/icons-material/Search";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import IconButton from "@mui/material/IconButton";
import InputAdornment from "@mui/material/InputAdornment";
import TextField from "@mui/material/TextField";

import DatabaseFilterMenu from "./DatabaseFilterMenu";

import type { CatalogueDatabase } from "../types";

interface CatalogueToolbarProps {
  query: string;
  onQueryChange: (query: string) => void;
  databases: CatalogueDatabase[];
  excludedDatabases: ReadonlySet<string>;
  onExcludedDatabasesChange: (excluded: ReadonlySet<string>) => void;
  onExpandAll: () => void;
  onCollapseAll: () => void;
}

/** Narrowing what the tree shows: a search, the databases to look in, and opening it all at once. */
export default function CatalogueToolbar({ query, onQueryChange, databases, excludedDatabases,
  onExcludedDatabasesChange, onExpandAll, onCollapseAll }: CatalogueToolbarProps) {
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: { xs: 1, sm: 1.5 }, flexWrap: "wrap" }}>
      <TextField
        size="small"
        value={query}
        onChange={event => { onQueryChange(event.target.value); }}
        placeholder="Filter by name or description…"
        // A row of its own on a phone, rather than fighting the databases button for the space
        sx={{ flex: 1, minWidth: { xs: "100%", sm: 280 }, maxWidth: { xs: "none", sm: 520 } }}
        slotProps={{
          htmlInput: { "aria-label": "Filter fields" },
          input: {
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon sx={{ fontSize: 18, color: "text.secondary" }} />
              </InputAdornment>
            ),
            endAdornment: query
              ? (
                <InputAdornment position="end">
                  <IconButton
                    size="small"
                    onClick={() => { onQueryChange(""); }}
                    title="Clear filter"
                    aria-label="Clear filter"
                  >
                    <ClearIcon sx={{ fontSize: 16 }} />
                  </IconButton>
                </InputAdornment>
              )
              : null,
          },
        }}
      />
      <DatabaseFilterMenu
        databases={databases}
        excluded={excludedDatabases}
        onChange={onExcludedDatabasesChange}
      />
      <Box sx={{ flex: 1, display: { xs: "none", sm: "block" } }} />
      {/* Kept as a pair so one does not orphan onto its own line when the row wraps */}
      <Box sx={{ display: "flex", gap: 0.5, flex: "none" }}>
        <Button size="small" onClick={onExpandAll}>Expand all</Button>
        <Button size="small" onClick={onCollapseAll}>Collapse all</Button>
      </Box>
    </Box>
  );
}
