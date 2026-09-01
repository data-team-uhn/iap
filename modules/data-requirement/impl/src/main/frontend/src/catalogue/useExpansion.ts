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

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

// A collection opens during a search only up to this many matches, so one large match does not bury
// the others
const AUTO_EXPAND_FIELD_LIMIT = 40;

export interface ExpansionApi {
  toggle: (key: string) => void;
  expandAll: (keys: readonly string[]) => void;
  collapseAll: () => void;
  /** Whether a database draws open. Every matching one opens during a search, so no result is
   * hidden behind a shut node. */
  isDatabaseOpen: (key: string, searching: boolean) => boolean;
  /** The same for a collection, which auto-opens only for a manageable number of matches. */
  isCollectionOpen: (key: string, searching: boolean, matchCount: number) => boolean;
}

/** Which nodes of the tree are open. */
export function useExpansion(defaultOpenKeys: readonly string[] = []): ExpansionApi {
  const [ open, setOpen ] = useState<ReadonlySet<string>>(() => new Set(defaultOpenKeys));
  const seeded = useRef(defaultOpenKeys.length > 0);

  // The catalogue, and so what starts open, can arrive after this mounts. Seeded once only, so a
  // later render does not undo what the reader opened or shut
  useEffect(() => {
    if (seeded.current || defaultOpenKeys.length === 0) {
      return;
    }
    seeded.current = true;
    setOpen(new Set(defaultOpenKeys));
  }, [ defaultOpenKeys ]);

  const toggle = useCallback((key: string) => {
    setOpen(current => {
      const next = new Set(current);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }, []);

  const expandAll = useCallback((keys: readonly string[]) => {
    setOpen(new Set(keys));
  }, []);

  const collapseAll = useCallback(() => {
    setOpen(new Set());
  }, []);

  return useMemo<ExpansionApi>(() => ({
    toggle,
    expandAll,
    collapseAll,
    isDatabaseOpen: (key, searching) => open.has(key) || searching,
    isCollectionOpen: (key, searching, matchCount) =>
      open.has(key) || (searching && matchCount <= AUTO_EXPAND_FIELD_LIMIT),
  }), [ open, toggle, expandAll, collapseAll ]);
}
