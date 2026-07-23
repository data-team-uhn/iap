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

import MySubmissionsWidget from "@iap/submissions/MySubmissionsWidget";

describe("MySubmissionsWidget", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("lists the current user's submissions with the schema and status columns", async () => {
    const page = {
      rows: [{
        "@path": "/Submissions/s1",
        "@name": "s1",
        "title": "Test my drug",
        "status": "in-review",
        "schemaVersion": { "@path": "/Schemas/ClinicalTrial/1.0", "@name": "1.0", "version": "1.0" },
        "jcr:created": "2026-07-01T10:00:00.000-04:00",
        "jcr:lastModified": "2026-07-02T10:00:00.000-04:00",
      }],
      offset: 0,
      limit: 5,
      returnedrows: 1,
      totalrows: 1,
      totalIsApproximate: false,
    };
    const fetchMock = vi.fn<(url: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(
      { ok: true, json: () => Promise.resolve(page) } as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);

    render(<MySubmissionsWidget />);

    expect(await screen.findByText("Test my drug")).toBeInTheDocument();
    expect(screen.getByText("ClinicalTrial 1.0")).toBeInTheDocument();
    expect(screen.getByText("in-review")).toBeInTheDocument();

    const url = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(url.pathname).toBe("/Submissions.paginate.json");
    expect(url.searchParams.getAll("fieldName")).toEqual(["jcr:createdBy"]);
    expect(url.searchParams.getAll("fieldValue")).toEqual(["@me"]);
    // Newest activity first by default
    expect(url.searchParams.get("sortBy")).toBe("jcr:lastModified");
    expect(url.searchParams.get("descending")).toBe("true");
  });
});
