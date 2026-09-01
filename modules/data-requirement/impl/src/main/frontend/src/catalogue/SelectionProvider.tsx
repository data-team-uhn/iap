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
  /** The chosen keys. */
  value: readonly string[];
  /** What the selection now is, every time it changes. */
  onChange: (keys: string[]) => void;
}

/**
 * Holds what has been chosen — or rather, does not: the host holds it, and this reports changes.
 *
 * The catalogue owns no copy of the selection and persists nothing. Inside a submission a change is
 * a request that can be refused, needs its own saving state and has to be told apart from a change
 * somebody else made; none of that belongs to a tree of checkboxes. What is left here is the
 * arithmetic of turning a click into the selection it produces.
 *
 * **A change that changes nothing is not reported.** Ticking a box that is already ticked, or
 * clearing a selection that is already empty, would otherwise cost a save each — and a save is a
 * round trip that can fail, so the noise would be visible.
 */
export function SelectionProvider({ children, value, onChange }: SelectionProviderProps) {
  const selected = useMemo(() => new Set(value), [ value ]);

  const report = useCallback((next: Set<string>) => {
    onChange([ ...next ]);
  }, [ onChange ]);

  const toggleField = useCallback((key: string, on?: boolean) => {
    const shouldSelect = on ?? !selected.has(key);
    if (shouldSelect === selected.has(key)) {
      return;
    }
    const next = new Set(selected);
    if (shouldSelect) {
      next.add(key);
    } else {
      next.delete(key);
    }
    report(next);
  }, [ selected, report ]);

  const setMany = useCallback((keys: readonly string[], on: boolean) => {
    const next = new Set(selected);
    let changed = false;
    for (const key of keys) {
      if (on ? !next.has(key) : next.has(key)) {
        if (on) {
          next.add(key);
        } else {
          next.delete(key);
        }
        changed = true;
      }
    }
    if (changed) {
      report(next);
    }
  }, [ selected, report ]);

  const replace = useCallback((keys: readonly string[]) => {
    const next = new Set(keys);
    // Compared rather than reported outright: swapping a selection for the same one is a change
    // nobody made, and the host would save it
    if (next.size === selected.size && [ ...next ].every(key => selected.has(key))) {
      return;
    }
    report(next);
  }, [ selected, report ]);

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
    setMany,
    replace,
    clear,
  }), [ selected, toggleField, setMany, replace, clear ]);

  return <SelectionContext.Provider value={context}>{children}</SelectionContext.Provider>;
}
