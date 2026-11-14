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
  /** The catalogue to show; absent while it is being read, or if it could not be. */
  catalogue?: Catalogue;
  loading?: boolean;
  /** Why the catalogue could not be read, as a message for the reader; `null` if nothing went wrong. */
  error?: string | null;
  /** The host's overrides of the default display config. */
  display?: DisplayOverride;
}

/** Hands the tree a catalogue, whether it is still loading, and how to display it. */
export function CatalogueProvider({ children, catalogue, loading = false, error = null, display }:
CatalogueProviderProps) {
  const value = useMemo<CatalogueContextValue>(() => ({
    // An absent catalogue reads as an empty one, so nothing downstream checks for it
    catalogue: catalogue ?? EMPTY_CATALOGUE,
    loading,
    error,
    display: resolveDisplayConfig(display),
  }), [ catalogue, loading, error, display ]);

  return <CatalogueContext.Provider value={value}>{children}</CatalogueContext.Provider>;
}
