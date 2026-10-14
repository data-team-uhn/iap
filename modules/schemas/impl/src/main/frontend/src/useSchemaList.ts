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

import { useNode } from "@iap/frontend-commons/useNode";

import { listing, schemasOf, SCHEMAS_ROOT } from "./schemaModel";

export const schemaPageUrl = (name: string): string => `/admin/schemas/${encodeURIComponent(name)}`;

// A version's page, under its schema's
export const versionPageUrl = (schemaName: string, versionName: string): string =>
  `${schemaPageUrl(schemaName)}/${encodeURIComponent(versionName)}`;

// The page of a version compared with another
export const comparisonPageUrl = (schemaName: string, base: string, compared: string): string =>
  `${versionPageUrl(schemaName, compared)}?compare=${encodeURIComponent(base)}`;

// Every schema, with its versions: what the listing and the dashboard widget show.
export function useSchemaList() {
  const { value, loading, loadError, reload } = useNode(SCHEMAS_ROOT, listing(2), schemasOf);

  return { schemas: value ?? [], loading, loadError, reload };
}
