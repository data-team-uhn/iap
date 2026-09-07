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

import { useMemo, useState, type ReactNode } from "react";

import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import CircularProgress from "@mui/material/CircularProgress";

import { CatalogueProvider } from "./CatalogueProvider";
import CatalogueToolbar from "./components/CatalogueToolbar";
import CatalogueTree from "./components/CatalogueTree";
import SelectionPanel from "./components/SelectionPanel";
import { SelectionProvider } from "./SelectionProvider";
import { useCatalogue } from "./useCatalogue";
import { useCatalogueFilter } from "./useCatalogueFilter";
import { useExpansion } from "./useExpansion";

import type { DisplayOverride } from "./display";
import type { Catalogue } from "./types";

export interface DataCatalogueProps {
  /** What there is to choose from. Absent while it is still being read. */
  catalogue?: Catalogue;
  loading?: boolean;
  /** Why it could not be read. */
  error?: string | null;
  /** The chosen field keys. The host owns them. */
  value: readonly string[];
  onChange: (keys: string[]) => void;
  /**
   * Shows what was chosen without offering to change it: every control that would is disabled or
   * left out, and nothing is reported through `onChange`. What a reader gets on a request that can
   * no longer be edited, and what an administrator looking at a published version gets.
   */
  readOnly?: boolean;
  /** What a deployment wants shown differently. */
  display?: DisplayOverride;
  /** What goes at the foot of the selection panel: saving, sending, whatever the step needs. */
  actions?: ReactNode;
  /** A host's own alerts, shown among the catalogue's. */
  notices?: ReactNode;
}

/** Everything inside the providers, so it can read them. */
function Browser({ actions, notices }: { actions?: ReactNode; notices?: ReactNode }) {
  const { catalogue, loading, error } = useCatalogue();
  const [ query, setQuery ] = useState("");
  const [ excluded, setExcluded ] = useState<ReadonlySet<string>>(() => new Set());
  const filter = useCatalogueFilter(catalogue, query, excluded);

  // Every database open to begin with, so a catalogue does not open as a list of shut boxes. Read
  // once the catalogue arrives, which is later than this mounts
  const defaultOpen = useMemo(
    () => catalogue.databases.map(database => database.identifier), [ catalogue ]);
  const expansion = useExpansion(defaultOpen);

  const allKeys = useMemo(
    () => catalogue.databases.flatMap(database =>
      [ database.identifier, ...database.collections.map(collection => collection.key) ]),
    [ catalogue ]);

  if (error) {
    return <Alert severity="error">{error}</Alert>;
  }
  if (loading) {
    return <CircularProgress aria-label="Loading the catalogue" />;
  }

  return (
    <Box sx={{ display: "flex", minHeight: 0, border: 1, borderColor: "divider", borderRadius: 1 }}>
      <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 1.5, p: 1.5 }}>
        {notices}
        <CatalogueToolbar
          query={query}
          onQueryChange={setQuery}
          databases={catalogue.databases}
          excludedDatabases={excluded}
          onExcludedDatabasesChange={setExcluded}
          onExpandAll={() => { expansion.expandAll(allKeys); }}
          onCollapseAll={expansion.collapseAll}
        />
        <CatalogueTree
          visible={filter.visible}
          expansion={expansion}
          query={query}
          // "Nobody has included one", not "there are none to include": offering to include them
          // all when the catalogue holds none would be a way out of nowhere
          noDatabasesIncluded={catalogue.databases.length > 0 && filter.includedDatabases.length === 0}
          onClearQuery={() => { setQuery(""); }}
          onIncludeAllDatabases={() => { setExcluded(new Set()); }}
        />
      </Box>
      <SelectionPanel actions={actions} />
    </Box>
  );
}

/**
 * Browsing a catalogue and choosing fields out of it.
 *
 * The one component a host renders. It owns no catalogue and no selection — both arrive as props,
 * and what is chosen is reported back rather than kept — which is what lets the same interface sit
 * inside a submission, on an administrator's screen, or anywhere else the two can be supplied.
 */
export default function DataCatalogue({ catalogue, loading, error, value, onChange, display,
  actions, notices, readOnly }: DataCatalogueProps) {
  return (
    <CatalogueProvider catalogue={catalogue} loading={loading} error={error} display={display}>
      <SelectionProvider value={value} onChange={onChange} readOnly={readOnly}>
        <Browser actions={actions} notices={notices} />
      </SelectionProvider>
    </CatalogueProvider>
  );
}
