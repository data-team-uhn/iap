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

import type { TagDefinition } from "@iap/tags/tagDefinitions";

// The lifecycle and review tag definitions as the instance would serve them, all shipped by
// the submissions module.
export const TAG_DEFINITIONS: (TagDefinition & { category: string[] })[] = [
  { name: "draft", label: "Draft", color: "#9e9e9e", order: 10, category: ["lifecycle"] },
  { name: "submitted", label: "Submitted", color: "#1976d2", order: 20, category: ["lifecycle"] },
  { name: "in-review", label: "In review", color: "#ed6c02", order: 30, category: ["lifecycle"] },
  { name: "approved", label: "Approved", color: "#2e7d32", order: 40, category: ["lifecycle", "review"] },
  { name: "rejected", label: "Rejected", color: "#d32f2f", order: 50, category: ["lifecycle", "review"] },
  { name: "in-progress", label: "In progress", color: "#0288d1", order: 60, category: ["review"] },
  { name: "changes-requested", label: "Changes requested", color: "#f57c00", order: 70, category: ["review"] },
];

// A fetch stand-in that answers the tag definition search like the TagListServlet (filtered by
// the category parameter, in definition order), and every other URL with the given payload.
export function tagAwareFetch(payload: unknown): (url: string) => Promise<Response> {
  return url => {
    let body: unknown = payload;
    if (url.includes("/Tags.search.json")) {
      const category = new URL(url, "http://localhost").searchParams.get("category");
      const tags = TAG_DEFINITIONS.filter(tag => category == null || tag.category.includes(category));
      body = { tags, total: tags.length };
    }
    return Promise.resolve({ ok: true, json: () => Promise.resolve(body) } as unknown as Response);
  };
}
