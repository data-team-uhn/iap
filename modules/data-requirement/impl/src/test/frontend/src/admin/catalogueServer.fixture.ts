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

import { jsonResponse, stubFetch } from "@iap/frontend-commons/fetch.fixture";

// A stand-in server for the catalogue tool's tests. It serves the catalogues, the lifecycle tag definitions
// and a live session, and records every event posted. It answers each event as a test asks: completed, or
// refused with the engine's reason.

export interface EventAnswer {
  status: number;
  error?: string;
}

export interface PostedEvent {
  url: string;
}

// Each node carries the `@events` the real guards would offer an administrator in its state
const OPEN_CATALOGUE = [ "retire" ];
const RETIRED_CATALOGUE = [ "activate" ];
const DRAFT = [ "activate" ];
const ACTIVE = [ "retire" ];
const RETIRED = [ "activate" ];

export const HOMEPAGE = {
  "jcr:primaryType": "datareq:CataloguesHomepage",
  "notACatalogue": { "jcr:primaryType": "nt:unstructured" },
  "registry": {
    "jcr:primaryType": "datareq:Catalogue",
    "title": "Clinical registry",
    "jcr:lastModified": "2026-09-20T10:00:00.000-04:00",
    "@events": OPEN_CATALOGUE,
    "v1": { "jcr:primaryType": "datareq:CatalogueVersion", "version": "2026-02", "tags": [ "retired" ],
      "@events": RETIRED },
    "v2": { "jcr:primaryType": "datareq:CatalogueVersion", "version": "2026-08", "description": "Current",
      "tags": [ "active" ], "@events": ACTIVE },
    "v3": { "jcr:primaryType": "datareq:CatalogueVersion", "version": "2026-11", "tags": [ "draft" ],
      "@events": DRAFT },
  },
  "legacy": {
    "jcr:primaryType": "datareq:Catalogue",
    "title": "Legacy warehouse",
    "tags": [ "retired" ],
    "@events": RETIRED_CATALOGUE,
    "v1": { "jcr:primaryType": "datareq:CatalogueVersion", "version": "1", "tags": [ "active" ], "@events": [] },
  },
};

const LIFECYCLE = {
  tags: [
    { name: "draft", label: "Draft" },
    { name: "active", label: "Active" },
    { name: "retired", label: "Retired" },
  ],
};

// A node as the serializer identifies it, with its children identified too
export function withPaths(path: string, node: Record<string, unknown>): Record<string, unknown> {
  const identified: Record<string, unknown> = { "@path": path, "@name": path.split("/").at(-1) };
  Object.entries(node).forEach(([ key, value ]) => {
    identified[key] = typeof value === "object" && value !== null && !Array.isArray(value)
      ? withPaths(`${path}/${key}`, value as Record<string, unknown>) : value;
  });
  return identified;
}

// Installs the server. `homepage` is what /Catalogues serves; `answers` picks the answer to an event by the URL
// it is posted to; `failReads` makes every read of catalogues fail with that status.
export function serveCatalogues(
  { homepage = HOMEPAGE, answers = {}, failReads }: {
    homepage?: Record<string, unknown>;
    answers?: Record<string, EventAnswer>;
    failReads?: number;
  } = {},
): PostedEvent[] {
  const posted: PostedEvent[] = [];
  stubFetch(({ url, method }) => {
    if (url.startsWith("/Tags.search.json")) {
      return jsonResponse(LIFECYCLE);
    }
    // Only the app's own requests: the data grid also posts its vendor's telemetry
    if (method === "POST" && url.startsWith("/")) {
      posted.push({ url });
      const answer = answers[url];
      return answer ? jsonResponse(answer.error ? { error: answer.error } : {}, answer.status)
        : jsonResponse({ status: "completed" });
    }
    if (failReads) {
      return jsonResponse({}, failReads);
    }
    if (url.startsWith("/Catalogues.paginate.json")) {
      const rows = Object.entries(homepage)
        .filter(([, value]) => (value as Record<string, unknown>)["jcr:primaryType"] === "datareq:Catalogue")
        .map(([name, value]) => withPaths(`/Catalogues/${name}`, value as Record<string, unknown>));
      return jsonResponse({ rows, offset: 0, limit: 25, returnedrows: rows.length, totalrows: rows.length,
        totalIsApproximate: false });
    }
    const name = /^\/Catalogues\/([^./]+)\./.exec(url)?.[1] ?? "";
    return name in homepage
      ? jsonResponse(withPaths(`/Catalogues/${name}`, homepage[name] as Record<string, unknown>))
      : jsonResponse({}, 404);
  });
  return posted;
}
