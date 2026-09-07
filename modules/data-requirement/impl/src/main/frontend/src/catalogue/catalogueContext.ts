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

import type { DisplayConfig } from "./display";
import type { Catalogue } from "./types";

export interface CatalogueContextValue {
  catalogue: Catalogue;
  /** While it is still being read. */
  loading: boolean;
  /** Why it could not be read, or `null` when it was. */
  error: string | null;
  /** How much of each field this catalogue shows. */
  display: DisplayConfig;
}

export const CatalogueContext = createContext<CatalogueContextValue | undefined>(undefined);
