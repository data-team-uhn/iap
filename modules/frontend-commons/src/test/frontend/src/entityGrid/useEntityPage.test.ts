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

import { renderHook, waitFor } from "@testing-library/react";

import useEntityPage from "@iap/frontend-commons/entityGrid/useEntityPage";

const EMPTY_PAGE = { rows: [], offset: 0, limit: 10, returnedrows: 0, totalrows: 0, totalIsApproximate: false };

const servingEmptyPages = () => {
  const fetchMock = vi.fn<(url: string) => Promise<Response>>(() => Promise.resolve(
    { ok: true, url: "", json: () => Promise.resolve(EMPTY_PAGE) } as unknown as Response));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

describe("useEntityPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("keeps a caller's group apart from a column filter's group of the same name", async () => {
    const fetchMock = servingEmptyPages();

    renderHook(() => useEntityPage({
      config: { homepage: "/Submissions", columns: [] },
      paginationModel: { page: 0, pageSize: 10 },
      descending: false,
      filters: [
        { name: "status", value: "draft", group: "item0" },
        { name: "status", value: "submitted", group: "item0" },
      ],
      columnFilters: [ { name: "title", comparator: "ILIKE", value: "%study%", group: "item0" } ],
      fullText: "",
      refreshToken: 0,
    }));

    await waitFor(() => { expect(fetchMock).toHaveBeenCalled(); });
    const groups = new URL(fetchMock.mock.calls[0][0], "http://localhost").searchParams.getAll("fieldGroup");
    expect(groups).toHaveLength(3);
    // The caller's two statuses are still ORed with each other, and the title stands apart
    expect(groups[1]).toBe(groups[0]);
    expect(groups[2]).not.toBe(groups[0]);
  });
});
