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

import { useCallback, useLayoutEffect, useMemo, useRef, type ReactNode } from "react";

import { SelectionContext, type SelectionContextValue } from "./selectionContext";

interface SelectionProviderProps {
  children: ReactNode;
  /** The keys of the chosen fields, as `database/collection/field`. */
  value: readonly string[];
  /** Called with the keys of the whole new selection after each change. */
  onChange: (keys: string[]) => void;
  /** Shows the selection without letting it change. */
  readOnly?: boolean;
}

/**
 * Turns clicks into the selection they produce, and reports it to the host, which owns it. A change
 * that changes nothing is not reported.
 */
export function SelectionProvider({ children, value, onChange, readOnly = false }:
SelectionProviderProps) {
  const selected = useMemo(() => new Set(value), [ value ]);

  // The actions read the latest selection and host through this, so they keep one identity and a
  // memoised row does not draw again each time anything is ticked
  const latest = useRef({ selected, onChange, readOnly });
  useLayoutEffect(() => {
    latest.current = { selected, onChange, readOnly };
  });

  // Every change goes through here, so read-only is enforced in one place
  const report = useCallback((next: Set<string>) => {
    if (!latest.current.readOnly) {
      latest.current.onChange([ ...next ]);
    }
  }, []);

  const selectFields = useCallback((keys: readonly string[]) => {
    const current = latest.current.selected;
    const next = new Set([ ...current, ...keys ]);
    if (next.size > current.size) {
      report(next);
    }
  }, [ report ]);

  const deselectFields = useCallback((keys: readonly string[]) => {
    const current = latest.current.selected;
    const dropped = new Set(keys);
    const next = new Set([ ...current ].filter(key => !dropped.has(key)));
    if (next.size < current.size) {
      report(next);
    }
  }, [ report ]);

  const toggleField = useCallback((key: string) => {
    if (latest.current.selected.has(key)) {
      deselectFields([ key ]);
    } else {
      selectFields([ key ]);
    }
  }, [ selectFields, deselectFields ]);

  const clear = useCallback(() => {
    if (latest.current.selected.size > 0) {
      report(new Set());
    }
  }, [ report ]);

  const context = useMemo<SelectionContextValue>(() => ({
    selected,
    readOnly,
    toggleField,
    selectFields,
    deselectFields,
    clear,
  }), [ selected, readOnly, toggleField, selectFields, deselectFields, clear ]);

  return <SelectionContext.Provider value={context}>{children}</SelectionContext.Provider>;
}
