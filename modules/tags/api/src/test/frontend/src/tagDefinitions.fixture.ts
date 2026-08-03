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
  { name: "draft", label: "Draft", color: "#4c5670", icon: "EditOutlined", order: 10, category: ["lifecycle"] },
  { name: "submitted", label: "Submitted", color: "#1b4f8f", icon: "SendOutlined",
    order: 20, category: ["lifecycle"] },
  { name: "in-review", label: "In review", color: "#55408f", icon: "VisibilityOutlined",
    order: 30, category: ["lifecycle"] },
  { name: "in-progress", label: "In progress", color: "#0b5b85", icon: "HourglassEmptyOutlined",
    order: 40, category: ["review"] },
  { name: "changes-requested", label: "Changes requested", color: "#8a5410", icon: "ReplyOutlined",
    order: 50, category: ["lifecycle", "review"] },
  { name: "approved", label: "Approved", color: "#1d6a3a", icon: "CheckCircleOutlined",
    order: 60, category: ["lifecycle", "review"] },
  { name: "rejected", label: "Rejected", color: "#8e1b29", icon: "CancelOutlined",
    order: 70, category: ["lifecycle", "review"] },
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
    return Promise.resolve({ ok: true, url: "", json: () => Promise.resolve(body) } as unknown as Response);
  };
}
