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
  /** The catalogue to choose from; absent while it is being read, or if it could not be. */
  catalogue?: Catalogue;
  loading?: boolean;
  /** Why the catalogue could not be read, as a message for the reader; `null` if nothing went wrong. */
  error?: string | null;
  /** The keys of the chosen fields, as `database/collection/field`. The host owns them. */
  value: readonly string[];
  /** Called with the keys of the whole new selection after each change. */
  onChange: (keys: string[]) => void;
  /** The host's overrides of the default display config. */
  display?: DisplayOverride;
  /** Controls the host puts at the foot of the selection panel, such as a Save button. */
  actions?: ReactNode;
  /** The host's own messages, shown above the toolbar. */
  notices?: ReactNode;
}

/** Everything inside the providers, so it can read them. */
function Browser({ actions, notices }: { actions?: ReactNode; notices?: ReactNode }) {
  const { catalogue, loading, error } = useCatalogue();
  const [ query, setQuery ] = useState("");
  const [ excluded, setExcluded ] = useState<ReadonlySet<string>>(() => new Set());
  const filter = useCatalogueFilter(catalogue, query, excluded);

  // Every database starts open. The catalogue can arrive after this mounts, and `useExpansion` takes
  // these when it does
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
          // Only where there is a database to include again
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
 * Browsing a catalogue and choosing fields from it. The host supplies the catalogue and owns the
 * selection, and each change is reported through `onChange`.
 */
export default function DataCatalogue({ catalogue, loading, error, value, onChange, display,
  actions, notices }: DataCatalogueProps) {
  return (
    <CatalogueProvider catalogue={catalogue} loading={loading} error={error} display={display}>
      <SelectionProvider value={value} onChange={onChange}>
        <Browser actions={actions} notices={notices} />
      </SelectionProvider>
    </CatalogueProvider>
  );
}
