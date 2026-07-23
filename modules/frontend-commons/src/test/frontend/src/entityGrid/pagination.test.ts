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

import { fetchEntityPage } from "@iap/frontend-commons/entityGrid/pagination";

const EMPTY_PAGE = {
  rows: [],
  offset: 0,
  limit: 5,
  returnedrows: 0,
  totalrows: 0,
  totalIsApproximate: false,
};

describe("fetchEntityPage", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("requests the right page from the homepage's pagination servlet", async () => {
    const fetchMock = vi.fn<(url: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(
      { ok: true, json: () => Promise.resolve(EMPTY_PAGE) } as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);

    await fetchEntityPage({
      homepage: "/Submissions",
      offset: 10,
      limit: 5,
      sortBy: "jcr:lastModified",
      descending: true,
      fullText: "cancer",
      filters: [{ name: "jcr:createdBy", value: "@me" }, { name: "status", value: "draft", comparator: "<>" }],
      childFilter: { type: "sub:Review", filters: [{ name: "reviewer", value: "@me" }] },
      req: "3",
    });

    const url = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(url.pathname).toBe("/Submissions.paginate.json");
    const params = url.searchParams;
    expect(params.get("offset")).toBe("10");
    expect(params.get("limit")).toBe("5");
    expect(params.get("sortBy")).toBe("jcr:lastModified");
    expect(params.get("descending")).toBe("true");
    expect(params.get("filter")).toBe("cancer");
    expect(params.getAll("fieldName")).toEqual(["jcr:createdBy", "status"]);
    expect(params.getAll("fieldComparator")).toEqual(["=", "<>"]);
    expect(params.getAll("fieldValue")).toEqual(["@me", "draft"]);
    expect(params.get("childType")).toBe("sub:Review");
    expect(params.getAll("childFieldName")).toEqual(["reviewer"]);
    expect(params.getAll("childFieldComparator")).toEqual(["="]);
    expect(params.getAll("childFieldValue")).toEqual(["@me"]);
    expect(params.get("req")).toBe("3");
  });

  it("omits the optional parameters when not provided", async () => {
    const fetchMock = vi.fn<(url: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(
      { ok: true, json: () => Promise.resolve(EMPTY_PAGE) } as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);

    const page = await fetchEntityPage({ homepage: "/Submissions" });

    const url = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    const params = url.searchParams;
    expect(params.get("offset")).toBe("0");
    for (const name of ["limit", "sortBy", "descending", "filter", "fieldName", "childType", "req"]) {
      expect(params.get(name)).toBeNull();
    }
    expect(page).toEqual(EMPTY_PAGE);
  });

  it("reports server errors", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: RequestInfo | URL) => Promise<Response>>(
      () => Promise.resolve({ ok: false, status: 400 } as unknown as Response)));
    await expect(fetchEntityPage({ homepage: "/Submissions" }))
      .rejects.toThrow("Failed to list /Submissions: 400");
  });
});
