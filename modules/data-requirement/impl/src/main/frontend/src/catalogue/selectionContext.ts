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

import { createContext } from "react";

/** What a checkbox standing for a whole group shows. */
export type CheckState = "none" | "some" | "all";

export function checkStateFor(selectedCount: number, total: number): CheckState {
  if (selectedCount === 0 || total === 0) {
    return "none";
  }
  return selectedCount >= total ? "all" : "some";
}

export interface SelectionContextValue {
  /** The chosen field keys. */
  selected: ReadonlySet<string>;
  count: number;
  /**
   * Whether what was chosen may still be changed.
   *
   * Read by every control that would change it, rather than passed down to each: a control that
   * cannot act has to *look* as though it cannot, or a reader is left clicking something that
   * quietly does nothing. The mutators below refuse as well, so that a path nobody disabled — a
   * stale closure, a keyboard shortcut added later — cannot write either.
   */
  readOnly: boolean;
  isSelected: (key: string) => boolean;
  /** Turns one field on or off, or flips it when `on` is not given. */
  toggleField: (key: string, on?: boolean) => void;
  /** Turns a whole collection or database on or off in one change. */
  setMany: (keys: readonly string[], on: boolean) => void;
  /** Swaps the whole selection for another. */
  replace: (keys: readonly string[]) => void;
  clear: () => void;
}

export const SelectionContext = createContext<SelectionContextValue | undefined>(undefined);
