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

import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";

// A stand-in server for the schema tool's tests: it serves the schemas, the lifecycle tag
// definitions and a live session, records every event posted, and answers each event as a test
// asks - completed, refused with the engine's reason, or redirected to what it created.

export interface EventAnswer {
  status?: number;
  error?: string;
  // Where the engine redirected to, which fetch has already followed
  redirect?: string;
}

export interface PostedEvent {
  url: string;
  params: URLSearchParams;
}

// Each node carries the `@events` the real guards would offer an administrator in its state
const OPEN_SCHEMA = [ "createVersion", "discard", "retire", "update" ];
const RETIRED_SCHEMA = [ "activate", "discard", "update" ];
const DRAFT = [ "activate", "discard", "update" ];
const ACTIVE = [ "discard", "retire", "update" ];
const RETIRED = [ "activate", "discard", "update" ];

// And the `@fields` their updates would change
const TITLE = [ { name: "title", label: "Title", kind: "text", mandatory: true, multiline: false } ];
const DESCRIPTION = { name: "description", label: "Description", kind: "text", mandatory: false, multiline: true };
const DRAFT_FIELDS = [
  { name: "version", label: "Label", kind: "text", mandatory: true, multiline: false }, DESCRIPTION,
  { name: "workflow", label: "Workflow", kind: "reference", mandatory: false, multiline: false },
];
const PUBLISHED_FIELDS = [ DESCRIPTION ];

export const HOMEPAGE = {
  "jcr:primaryType": "sch:SchemasHomepage",
  "notASchema": { "jcr:primaryType": "nt:unstructured" },
  "study": {
    "jcr:primaryType": "sch:Schema",
    "title": "Clinical study",
    "jcr:lastModified": "2026-09-20T10:00:00.000-04:00",
    "@events": OPEN_SCHEMA, "@fields": TITLE,
    "v1": { "jcr:primaryType": "sch:SchemaVersion", "version": "1.0", "tags": ["retired"], "@events": RETIRED, "@fields": PUBLISHED_FIELDS },
    "v2": {
      "jcr:primaryType": "sch:SchemaVersion", "version": "2.0", "description": "Current", "tags": ["active"],
      "@events": ACTIVE, "@fields": PUBLISHED_FIELDS,
    },
    "v3": { "jcr:primaryType": "sch:SchemaVersion", "version": "3.0", "tags": ["draft"], "@events": DRAFT, "@fields": DRAFT_FIELDS },
    "notes": { "jcr:primaryType": "nt:unstructured" },
  },
  "idea": {
    "jcr:primaryType": "sch:Schema",
    "title": "Idea",
    "@events": OPEN_SCHEMA, "@fields": TITLE,
    "v1": { "jcr:primaryType": "sch:SchemaVersion", "version": "0.1", "tags": ["draft"], "@events": DRAFT, "@fields": DRAFT_FIELDS },
  },
  "legacy": {
    "jcr:primaryType": "sch:Schema",
    "title": "Legacy",
    "tags": ["retired"],
    "@events": RETIRED_SCHEMA, "@fields": TITLE,
    "v1": { "jcr:primaryType": "sch:SchemaVersion", "version": "1.0", "tags": ["active"], "@events": ACTIVE, "@fields": PUBLISHED_FIELDS },
  },
};

const LIFECYCLE = {
  tags: [
    { name: "draft", label: "Draft" },
    { name: "active", label: "Active" },
    { name: "retired", label: "Retired" },
  ],
};

const json = (url: string, body: unknown, status = 200) => Promise.resolve({
  ok: status < 400, status, url, redirected: false, json: () => Promise.resolve(body),
} as unknown as Response);

// A node as the serializer identifies it, with its children identified too
export function withPaths(path: string, node: Record<string, unknown>): Record<string, unknown> {
  const identified: Record<string, unknown> = { "@path": path, "@name": path.split("/").at(-1) };
  Object.entries(node).forEach(([ key, value ]) => {
    identified[key] = typeof value === "object" && value !== null && !Array.isArray(value)
      ? withPaths(`${path}/${key}`, value as Record<string, unknown>) : value;
  });
  return identified;
}

// Installs the server. `homepage` is what /Schemas serves; a schema is served from its own entry.
// `answers` picks the answer to an event by the URL it is posted to; `failReads` makes every read of
// schemas fail with that status.
export function serveSchemas(
  { homepage = HOMEPAGE, answers = {}, failReads }: {
    homepage?: Record<string, unknown>;
    answers?: Record<string, EventAnswer>;
    failReads?: number;
  } = {},
): PostedEvent[] {
  const posted: PostedEvent[] = [];
  vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
    if (url === SESSION_INFO_URL) {
      return json(url, { userID: "admin" });
    }
    if (url.startsWith("/Tags.search.json")) {
      return json(url, LIFECYCLE);
    }
    // Only the app's own requests: the data grid also posts its vendor's telemetry
    if (init?.method === "POST" && url.startsWith("/")) {
      posted.push({ url, params: new URLSearchParams(init.body as URLSearchParams) });
      const answer = answers[url] ?? {};
      if (answer.redirect) {
        return Promise.resolve({
          ok: true, status: 200, url: `http://localhost${answer.redirect}`, redirected: true,
        } as unknown as Response);
      }
      return answer.status && answer.status >= 400
        ? json(url, answer.error ? { error: answer.error } : {}, answer.status)
        : json(url, { status: "completed" });
    }
    if (failReads) {
      return json(url, {}, failReads);
    }
    if (url.startsWith("/Schemas.paginate.json")) {
      const rows = Object.entries(homepage)
        .filter(([, value]) => (value as Record<string, unknown>)["jcr:primaryType"] === "sch:Schema")
        .map(([name, value]) => withPaths(`/Schemas/${name}`, value as Record<string, unknown>));
      return json(url, { rows, offset: 0, limit: 25, returnedrows: rows.length, totalrows: rows.length,
        totalIsApproximate: false });
    }
    if (url.startsWith("/Schemas.")) {
      return json(url, withPaths("/Schemas", homepage));
    }
    const name = /^\/Schemas\/([^./]+)\./.exec(url)?.[1] ?? "";
    return name in homepage
      ? json(url, withPaths(`/Schemas/${name}`, homepage[name] as Record<string, unknown>)) : json(url, {}, 404);
  }));
  return posted;
}
