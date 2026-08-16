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

// Client-side access to the tag definitions stored under /Tags: the single source of truth
// for what a tag means and how it is displayed. See the tags module's TagListServlet for the
// serving side.

// One tag definition, as served by /Tags.search.json
export interface TagDefinition {
  name: string;
  label?: string;
  color?: string;
  order?: number;
  category?: string[];
}

// The tag definitions are stable content, so they are fetched once per category and shared by
// every consumer on the page; a failed fetch resolves to "no definitions" and consumers show
// less rather than breaking their surroundings. Alongside the promises, the resolved lists are
// kept as a synchronous snapshot for consumers that cannot await, like tagValueOptions.
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
        const result = await response.json() as { tags?: TagDefinition[] };
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

// The choices a data grid column (or any other synchronous consumer) should offer when
// filtering by a category of tags: the defined tag names, labeled and colored like their
// definitions (colors are passed through raw; whoever places one into styles is responsible
// for whitelisting it, like TagChip does). Returns a provider function rather than the
// values, because the definitions are fetched asynchronously: each call answers from the
// snapshot fetched so far (and triggers the fetch, so a panel opened too early is at most
// one reopen away from the full list).
export function tagValueOptions(category: string): () => { value: string; label: string; color?: string }[] {
  return () => {
    void loadTagDefinitions(category);
    return (resolvedDefinitions.get(category) ?? [])
      .map(definition => ({
        value: definition.name,
        label: definition.label ?? definition.name,
        color: definition.color,
      }));
  };
}

// Forgets the fetched definitions; only meant for tests, which stub fetch per test.
export function clearTagDefinitionsCache(): void {
  definitionCache.clear();
  resolvedDefinitions.clear();
}
