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

import { schemasOf, SCHEMAS_ROOT } from "./schemaModel";
import { listing, useNode } from "./useNode";

export const schemaPageUrl = (name: string): string => `/admin/schemas/${encodeURIComponent(name)}`;

// A version's page: a query parameter rather than a path segment, since version names such as 1.0 have dots
export const versionPageUrl = (schemaName: string, versionName: string): string =>
  `${schemaPageUrl(schemaName)}?version=${encodeURIComponent(versionName)}`;

// Every schema, with its versions: what the listing and the dashboard widget show.
export function useSchemaList() {
  const { value, loading, loadError, reload } = useNode(SCHEMAS_ROOT, listing(2), schemasOf);

  return { schemas: value ?? [], loading, loadError, reload };
}
