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

import { readReferences, referenceChoices } from "@iap/frontend-commons/inputs/referenceChoices";
import { RequestError } from "@iap/frontend-commons/requestFailure";

const response = (body: unknown, status = 200) =>
  Promise.resolve({ ok: status < 400, status, url: "", json: () => Promise.resolve(body) } as unknown as Response);

const queryOf = (url: string) => new URL(url, "http://localhost").searchParams;

describe("readReferences", () => {
  it("reads at most a thousand nodes, sorted by their labels", async () => {
    const doFetch = vi.fn((_url: string) => response({ rows: [
      { "@path": "/Workflows/review/v1" }, { "@path": "/Workflows/fast/v2", "title": "Fast track" }, { "title": "?" },
    ] }));

    expect(await readReferences(doFetch, { resourceType: "wf/Version", root: "/Workflows" })).toEqual([
      { path: "/Workflows/fast/v2", label: "Fast track" }, { path: "/Workflows/review/v1", label: "review/v1" },
    ]);
    expect(queryOf(doFetch.mock.calls[0][0]).get("limit")).toBe("1000");
  });

  it("reads nothing without a type, and no rows as none", async () => {
    const doFetch = vi.fn((_url: string) => response({}));

    expect(await readReferences(doFetch, {})).toEqual([]);
    expect(doFetch).not.toHaveBeenCalled();
    expect(await readReferences(doFetch, { nodeType: "sch:Schema" })).toEqual([]);
  });

  it("fails as the server did", async () => {
    await expect(readReferences(() => response({}, 403), { nodeType: "sch:Schema" })).rejects.toBeInstanceOf(RequestError);
  });
});

describe("referenceChoices", () => {
  it("offers each node by its label, to be stored as its absolute path", async () => {
    const doFetch = vi.fn((_url: string) => response({ rows: [ { "@path": "/Schemas/intake", "title": "Intake" } ] }));
    const source = referenceChoices({ nodeType: "sch:Schema", root: "/Schemas" });

    expect(await source.search(doFetch)).toEqual([ { value: "/Schemas/intake", label: "Intake" } ]);
    expect(queryOf(doFetch.mock.calls[0][0]).get("query"))
      .toBe("select * from [sch:Schema] as n where isdescendantnode(n, '/Schemas')");
  });

  it("is the same source for the same nodes", () => {
    expect(referenceChoices({ nodeType: "sch:Schema" }).key).toBe(referenceChoices({ nodeType: "sch:Schema" }).key);
    expect(referenceChoices({ nodeType: "sch:Schema" }).key).not.toBe(referenceChoices({ resourceType: "sch:Schema" }).key);
  });
});
