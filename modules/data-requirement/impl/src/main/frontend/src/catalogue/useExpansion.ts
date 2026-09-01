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

import { useCallback, useMemo, useState } from "react";

// During a search, a collection with at most this many matches opens by itself. One with more stays
// closed, so it does not push the others out of sight
const AUTO_EXPAND_FIELD_LIMIT = 40;

export interface ExpansionApi {
  /** Opens or closes one node. */
  setOpen: (key: string, open: boolean) => void;
  /** Opens every node named. */
  expandAll: (keys: readonly string[]) => void;
  /** Closes every node named. */
  collapseAll: (keys: readonly string[]) => void;
  isDatabaseOpen: (key: string) => boolean;
  /** Whether a collection is open, given how many of its fields the search matched. */
  isCollectionOpen: (key: string, matchCount: number) => boolean;
}

// What the reader opened or closed during the current search. It is dropped when the search ends, so the
// tree goes back to how the reader left it.
interface SearchChoices {
  searching: boolean;
  open: ReadonlyMap<string, boolean>;
}

/**
 * Which nodes of the tree are open. During a search every database opens, and every collection whose
 * matches are few, unless the reader closes it.
 */
export function useExpansion(defaultOpenKeys: readonly string[], searching: boolean): ExpansionApi {
  const [ open, setOpenKeys ] = useState<ReadonlySet<string>>(() => new Set(defaultOpenKeys));
  const [ choices, setChoices ] = useState<SearchChoices>({ searching, open: new Map() });
  if (choices.searching !== searching) {
    setChoices({ searching, open: new Map() });
  }

  const choose = useCallback((keys: readonly string[], isOpen: boolean) => {
    if (searching) {
      setChoices(current => ({
        searching: current.searching,
        open: new Map([ ...current.open, ...keys.map(key => [ key, isOpen ] as const) ]),
      }));
      return;
    }
    setOpenKeys(current => {
      const next = new Set(current);
      keys.forEach(key => (isOpen ? next.add(key) : next.delete(key)));
      return next;
    });
  }, [ searching ]);

  const setOpen = useCallback((key: string, isOpen: boolean) => { choose([ key ], isOpen); }, [ choose ]);
  const expandAll = useCallback((keys: readonly string[]) => { choose(keys, true); }, [ choose ]);
  const collapseAll = useCallback((keys: readonly string[]) => { choose(keys, false); }, [ choose ]);

  return useMemo<ExpansionApi>(() => ({
    setOpen,
    expandAll,
    collapseAll,
    isDatabaseOpen: key => (searching ? choices.open.get(key) ?? true : open.has(key)),
    isCollectionOpen: (key, matchCount) => (searching
      ? choices.open.get(key) ?? (open.has(key) || matchCount <= AUTO_EXPAND_FIELD_LIMIT)
      : open.has(key)),
  }), [ open, choices, searching, setOpen, expandAll, collapseAll ]);
}
