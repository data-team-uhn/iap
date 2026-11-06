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

import { readJson } from "@iap/frontend-commons/requestFailure";

// Reads the tag definitions under /Tags, which say what each tag means and how it is shown.

// One tag definition, as /Tags.search.json serves it. `variant` is "soft" when absent,
// "outlined" or "filled", and `icon` is a MUI icon name (see tagIcons).
export interface TagDefinition {
  name: string;
  label?: string;
  color?: string;
  variant?: string;
  icon?: string;
  order?: number;
  category?: string[];
}

// The definitions are fetched once per category and shared by everything on the page. The
// resolved lists are also kept for tagValueOptions, which cannot wait for a promise.
const definitionCache = new Map<string, Promise<TagDefinition[]>>();
const resolvedDefinitions = new Map<string, TagDefinition[]>();

// The definitions of the given category, or all defined tags when no category is given, in
// the definitions' own order. Never rejects: fetch failures resolve to an empty list.
export function loadTagDefinitions(
  category?: string, fetchUtil: (url: string) => Promise<Response> = fetch): Promise<TagDefinition[]> {
  const cacheKey = category ?? "*";
  let cached = definitionCache.get(cacheKey);
  if (!cached) {
    cached = fetchUtil(category ? `/Tags.search.json?category=${encodeURIComponent(category)}` : "/Tags.search.json")
      .then(async response => {
        if (!response.ok) {
          throw new Error(`Listing the tag definitions failed: ${response.status}`);
        }
        const result = await readJson<{ tags?: TagDefinition[] }>(response);
        return result.tags ?? [];
      })
      .catch(() => {
        // Show less for now, but do not cache the failure: the next consumer retries
        definitionCache.delete(cacheKey);
        return [];
      })
      .then(definitions => {
        resolvedDefinitions.set(cacheKey, definitions);
        return definitions;
      });
    definitionCache.set(cacheKey, cached);
  }
  return cached;
}

// The choices for filtering by the tags of one category, such as in a data grid column: the
// defined tag names, with their labels, colors and variants. A color is passed on as stored, and
// whatever puts it into a style must check it first, as chipStyle does. Returns a function
// rather than the choices, since the definitions arrive later and the caller asks synchronously.
//
// tagValueOptions starts the fetch itself, so that the choices are there before the returned
// function is first called. A filter input that finds none of its applied values among the
// choices shows none, and its next edit saves only what it shows. The returned function fetches
// again too, in case the cache was cleared.
export function tagValueOptions(
  category: string
): () => { value: string; label: string; color?: string; variant?: string }[] {
  void loadTagDefinitions(category);
  return () => {
    void loadTagDefinitions(category);
    return (resolvedDefinitions.get(category) ?? [])
      .map(definition => ({
        value: definition.name,
        label: definition.label ?? definition.name,
        color: definition.color,
        variant: definition.variant,
      }));
  };
}

// Forgets the fetched definitions, for tests that stub fetch per test
export function clearTagDefinitionsCache(): void {
  definitionCache.clear();
  resolvedDefinitions.clear();
}
