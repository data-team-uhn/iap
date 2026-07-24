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

import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import MyReviewQueueWidget from "@iap/submissions/MyReviewQueueWidget";

describe("MyReviewQueueWidget", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("asks for submissions with an open review assigned to the current user", async () => {
    const page = { rows: [], offset: 0, limit: 5, returnedrows: 0, totalrows: 0, totalIsApproximate: false };
    const fetchMock = vi.fn<(url: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(
      { ok: true, json: () => Promise.resolve(page) } as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);

    render(<MyReviewQueueWidget />, { wrapper: MemoryRouter });

    expect(await screen.findByText("No submissions to review")).toBeInTheDocument();

    const url = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(url.pathname).toBe("/Submissions.paginate.json");
    expect(url.searchParams.get("childType")).toBe("sub:Review");
    expect(url.searchParams.getAll("childFieldName")).toEqual(["reviewer", "status", "status"]);
    expect(url.searchParams.getAll("childFieldComparator")).toEqual(["=", "<>", "<>"]);
    expect(url.searchParams.getAll("childFieldValue")).toEqual(["@me", "approved", "rejected"]);
  });
});
