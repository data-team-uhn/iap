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

import { useState } from "react";

import type { GridColumnVisibilityModel } from "@mui/x-data-grid-pro";

// Which columns the user hid the last time they used a grid for this entity type.
function loadStoredColumnVisibility(storageKey: string): GridColumnVisibilityModel {
  try {
    const stored: unknown = JSON.parse(window.localStorage.getItem(storageKey) ?? "{}");
    return stored && typeof stored === "object" ? stored as GridColumnVisibilityModel : {};
  } catch {
    // Storage missing, disabled or holding something unreadable: every column shows
    return {};
  }
}

// Which columns the grids of one entity type hide, kept in localStorage, so that the choice lasts
// in this browser. The model names the hidden columns with `false`.
export default function useColumnVisibility(entityType: string)
  : [ GridColumnVisibilityModel, (model: GridColumnVisibilityModel) => void ] {
  const storageKey = `iap.entityGrid.${entityType}.columns`;
  const [model, setModel] = useState<GridColumnVisibilityModel>(() => loadStoredColumnVisibility(storageKey));
  const change = (next: GridColumnVisibilityModel) => {
    setModel(next);
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(next));
    } catch {
      // Storage may be disabled or full; the choice then lasts until the page is left
    }
  };
  return [ model, change ];
}
