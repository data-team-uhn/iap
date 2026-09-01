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

import { checkStateFor, type CheckState } from "./selectionContext";
import { useSelection } from "./useSelection";

export interface GroupSelection {
  /** How many of the group's fields are chosen. */
  selectedCount: number;
  /** What the group's checkbox shows. */
  state: CheckState;
  /** Turns the whole group on or off in one change. */
  setAll: (select: boolean) => void;
}

/**
 * A database's or a collection's view of the selection, over the keys it is given. During a search
 * those are the fields that matched, so ticking the group takes only what is shown.
 */
export function useGroupSelection(keys: readonly string[]): GroupSelection {
  const { selected, selectFields, deselectFields } = useSelection();
  const selectedCount = keys.reduce((total, key) => total + (selected.has(key) ? 1 : 0), 0);

  return {
    selectedCount,
    state: checkStateFor(selectedCount, keys.length),
    setAll: select => {
      if (select) {
        selectFields(keys);
      } else {
        deselectFields(keys);
      }
    },
  };
}
