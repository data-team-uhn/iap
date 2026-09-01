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

import { useCallback, useMemo, type ReactNode } from "react";

import { SelectionContext, type SelectionContextValue } from "./selectionContext";

interface SelectionProviderProps {
  children: ReactNode;
  /** The keys of the chosen fields, as `database/collection/field`. */
  value: readonly string[];
  /** Called with the keys of the whole new selection after each change. */
  onChange: (keys: string[]) => void;
}

/**
 * Turns clicks into the selection they produce, and reports it to the host, which owns it. A change
 * that changes nothing is not reported.
 */
export function SelectionProvider({ children, value, onChange }: SelectionProviderProps) {
  const selected = useMemo(() => new Set(value), [ value ]);

  const report = useCallback((next: Set<string>) => {
    onChange([ ...next ]);
  }, [ onChange ]);

  const selectFields = useCallback((keys: readonly string[]) => {
    const next = new Set([ ...selected, ...keys ]);
    if (next.size > selected.size) {
      report(next);
    }
  }, [ selected, report ]);

  const deselectFields = useCallback((keys: readonly string[]) => {
    const dropped = new Set(keys);
    const next = new Set([ ...selected ].filter(key => !dropped.has(key)));
    if (next.size < selected.size) {
      report(next);
    }
  }, [ selected, report ]);

  const toggleField = useCallback((key: string) => {
    if (selected.has(key)) {
      deselectFields([ key ]);
    } else {
      selectFields([ key ]);
    }
  }, [ selected, selectFields, deselectFields ]);

  const clear = useCallback(() => {
    if (selected.size > 0) {
      onChange([]);
    }
  }, [ selected, onChange ]);

  const context = useMemo<SelectionContextValue>(() => ({
    selected,
    count: selected.size,
    isSelected: (key: string) => selected.has(key),
    toggleField,
    selectFields,
    deselectFields,
    clear,
  }), [ selected, toggleField, selectFields, deselectFields, clear ]);

  return <SelectionContext.Provider value={context}>{children}</SelectionContext.Provider>;
}
