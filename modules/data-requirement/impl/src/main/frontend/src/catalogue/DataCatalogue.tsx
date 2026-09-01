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

import { useDeferredValue, useMemo, useRef, useState, type ReactNode } from "react";

import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import CircularProgress from "@mui/material/CircularProgress";

import { useElementWidth } from "@iap/frontend-commons/useElementWidth";

import { CatalogueProvider } from "./CatalogueProvider";
import CatalogueToolbar from "./components/CatalogueToolbar";
import CatalogueTree from "./components/CatalogueTree";
import SelectionPanel, { PANEL_WIDTH } from "./components/SelectionPanel";
import { SelectionProvider } from "./SelectionProvider";
import { useCatalogue } from "./useCatalogue";
import { useCatalogueFilter } from "./useCatalogueFilter";
import { useExpansion } from "./useExpansion";

import type { DisplayOverride } from "./display";
import type { Catalogue } from "./types";

// Below this width, the side panel would leave the tree too little room, so the selection moves to a
// bar under the tree that opens a drawer. The width is the catalogue's own, not the page's.
const COMPACT_BELOW = 2 * PANEL_WIDTH;

export interface DataCatalogueProps {
  /** The catalogue to choose from; absent while it is being read, or if it could not be. */
  catalogue?: Catalogue;
  /** Why the catalogue could not be read, as a message for the reader; `null` if nothing went wrong. */
  error?: string | null;
  /** The keys of the chosen fields, as `database/collection/field`. The host owns them. */
  value: readonly string[];
  /** Called with the keys of the whole new selection after each change. */
  onChange: (keys: string[]) => void;
  /** The host's overrides of the default display config. */
  display?: DisplayOverride;
  /** What the host shows beside its notices, such as how its save is going. It stays in view in every layout. */
  actions?: ReactNode;
  /** The host's own messages, shown above the toolbar. */
  notices?: ReactNode;
}

/** The browser itself, drawn inside the providers so that it can read them. */
function Browser({ actions, notices }: { actions?: ReactNode; notices?: ReactNode }) {
  const { catalogue } = useCatalogue();
  const [ query, setQuery ] = useState("");
  // The tree follows the search a beat behind the typing, so a large catalogue never holds up a keystroke
  const searchedFor = useDeferredValue(query);
  const [ excluded, setExcluded ] = useState<ReadonlySet<string>>(() => new Set());
  const filter = useCatalogueFilter(catalogue, searchedFor, excluded);
  // Every database starts open
  const expansion = useExpansion(catalogue.databases.map(database => database.identifier), filter.searching);
  const allKeys = useMemo(
    () => catalogue.databases.flatMap(database =>
      [ database.identifier, ...database.collections.map(collection => collection.key) ]),
    [ catalogue ]);

  const root = useRef<HTMLDivElement>(null);
  const width = useElementWidth(root);
  const compact = width !== undefined && width < COMPACT_BELOW;

  // An empty state's link disappears once clicked, so focus moves to the filter
  const filterInput = useRef<HTMLInputElement>(null);
  const thenFocusFilter = (reset: () => void) => () => {
    reset();
    filterInput.current?.focus();
  };

  return (
    <Box
      ref={root}
      sx={{
        display: "flex",
        flexDirection: compact ? "column" : "row",
        minHeight: 0,
        border: 1,
        borderColor: "divider",
        borderRadius: 1,
      }}
    >
      <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 1.5, p: 1.5 }}>
        {(Boolean(notices) || Boolean(actions)) && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}>
            <Box sx={{ flex: 1, minWidth: 0 }}>{notices}</Box>
            {actions}
          </Box>
        )}
        <CatalogueToolbar
          inputRef={filterInput}
          query={query}
          onQueryChange={setQuery}
          databases={catalogue.databases}
          excludedDatabases={excluded}
          onExcludedDatabasesChange={setExcluded}
          onExpandAll={() => { expansion.expandAll(allKeys); }}
          onCollapseAll={() => { expansion.collapseAll(allKeys); }}
        />
        <CatalogueTree
          visible={filter.visible}
          expansion={expansion}
          query={searchedFor}
          searching={filter.searching}
          noDatabasesIncluded={filter.noDatabasesIncluded}
          onClearQuery={thenFocusFilter(() => { setQuery(""); })}
          onIncludeAllDatabases={thenFocusFilter(() => { setExcluded(new Set()); })}
        />
      </Box>
      <SelectionPanel compact={compact} />
    </Box>
  );
}

/**
 * Browsing a catalogue and choosing fields from it. The host supplies the catalogue and owns the
 * selection, and each change is reported through `onChange`.
 */
export default function DataCatalogue({ catalogue, error, value, onChange, display,
  actions, notices }: DataCatalogueProps) {
  if (error) {
    return <Alert severity="error">{error}</Alert>;
  }
  if (!catalogue) {
    return <CircularProgress aria-label="Loading the catalogue" />;
  }
  return (
    <CatalogueProvider catalogue={catalogue} display={display}>
      <SelectionProvider value={value} onChange={onChange}>
        <Browser actions={actions} notices={notices} />
      </SelectionProvider>
    </CatalogueProvider>
  );
}
