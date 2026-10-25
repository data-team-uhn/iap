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

import { useStoredSetting } from "../useStoredSetting";

import type { GridColumnVisibilityModel } from "@mui/x-data-grid-pro";

const NONE_HIDDEN: GridColumnVisibilityModel = {};

const isVisibilityModel = (value: unknown): value is GridColumnVisibilityModel =>
  typeof value === "object" && value !== null && !Array.isArray(value)
    && Object.values(value).every(shown => typeof shown === "boolean");

// The column selection of the grids listing one entity type, remembered in localStorage.
export default function useColumnVisibility(entityType: string)
  : [ GridColumnVisibilityModel, (model: GridColumnVisibilityModel) => void ] {
  return useStoredSetting(`iap.entityGrid.${entityType}.columns`, NONE_HIDDEN, isVisibilityModel);
}
