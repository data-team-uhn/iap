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

import { CatalogueContext, type CatalogueContextValue } from "./catalogueContext";
import { resolveDisplayConfig, type DisplayOverride } from "./display";
import { EMPTY_CATALOGUE, type Catalogue } from "./types";

interface CatalogueProviderProps {
  children: ReactNode;
  /** What to show. Omitted while it is still being read. */
  catalogue?: Catalogue;
  loading?: boolean;
  error?: string | null;
  /** What a deployment wants shown differently. */
  display?: DisplayOverride;
}

/**
 * Puts a catalogue where the tree can read it.
 *
 * The catalogue arrives as data rather than being fetched here, which is what keeps this directory
 * free of any opinion about where one comes from — a repository, a file, a test. Whoever supplies it
 * also says whether it is still coming and what went wrong if it is not.
 */
export function CatalogueProvider({ children, catalogue, loading = false, error = null, display }:
CatalogueProviderProps) {
  const value = useMemo<CatalogueContextValue>(() => ({
    // An absent catalogue reads as an empty one, so that everything downstream can render a tree
    // with nothing in it rather than guarding against not having one at all
    catalogue: catalogue ?? EMPTY_CATALOGUE,
    loading,
    error,
    display: resolveDisplayConfig(display),
  }), [ catalogue, loading, error, display ]);

  return <CatalogueContext.Provider value={value}>{children}</CatalogueContext.Provider>;
}
