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

import { loadWorkflowCounts } from "@iap/workflows/useWorkflowCounts";
import { forgetWorkflowHomepages } from "@iap/workflows/useWorkflowHomepages";

type FetchStub = (url: string) => Promise<Response>;

const okResponse = (body: unknown) =>
  ({ ok: true, status: 200, json: () => Promise.resolve(body) } as unknown as Response);

const answering = (body: unknown) => vi.fn<FetchStub>(() => Promise.resolve(okResponse(body)));

describe("loadWorkflowCounts", () => {
  // It counts through the discovery, which is kept for the session
  beforeEach(forgetWorkflowHomepages);

  // The discovery endpoint, then one count per homepage it named.
  const server = (homepages: unknown[], counts: Record<string, unknown>) => vi.fn<FetchStub>(url =>
    Promise.resolve(okResponse(url === "/Workflows.homepages.json"
      ? { homepages }
      : counts[url.replace(".paginate.json?offset=0&limit=0", "")])));

  const page = (total: number, approximate = false) => ({
    rows: [], offset: 0, limit: 0, returnedrows: 0, totalrows: total, totalIsApproximate: approximate,
  });

  it("counts the workflows of each homepage without listing any of them", async () => {
    const fetchUtil = server(
      [ { path: "/Workflows", title: "Workflows" }, { path: "/SystemWorkflows", title: "System workflows" } ],
      { "/Workflows": page(3), "/SystemWorkflows": page(12) }
    );

    const counts = await loadWorkflowCounts(fetchUtil);

    expect(counts).toEqual([
      { path: "/Workflows", title: "Workflows", count: 3, atLeast: false },
      { path: "/SystemWorkflows", title: "System workflows", count: 12, atLeast: false },
    ]);
    // A page of no rows at all: the count is the whole answer
    expect(fetchUtil).toHaveBeenCalledWith("/Workflows.paginate.json?offset=0&limit=0");
    expect(fetchUtil).toHaveBeenCalledWith("/SystemWorkflows.paginate.json?offset=0&limit=0");
  });

  it("passes on that a count the server stopped short of finishing is a lower bound", async () => {
    const fetchUtil = server([ { path: "/Workflows", title: "Workflows" } ], { "/Workflows": page(100, true) });

    await expect(loadWorkflowCounts(fetchUtil)).resolves.toEqual([
      { path: "/Workflows", title: "Workflows", count: 100, atLeast: true },
    ]);
  });

  it("has nothing to count when there is nowhere workflows are stored", async () => {
    await expect(loadWorkflowCounts(answering({ homepages: [] }))).resolves.toEqual([]);
  });

  it("still names a homepage whose count was refused, without a number for it", async () => {
    // The homepages are independent, so one that can't be counted loses only its own number.
    // That it exists is still worth reporting, and is known either way.
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const fetchUtil = vi.fn<FetchStub>(url => {
      if (url === "/Workflows.homepages.json") {
        return Promise.resolve(okResponse({ homepages: [
          { path: "/Workflows", title: "Workflows" },
          { path: "/SystemWorkflows", title: "System workflows" },
        ] }));
      }
      return url.startsWith("/SystemWorkflows")
        ? Promise.resolve({ ok: false, status: 503, json: () => Promise.resolve({}) } as unknown as Response)
        : Promise.resolve(okResponse(page(3)));
    });

    await expect(loadWorkflowCounts(fetchUtil)).resolves.toEqual([
      { path: "/Workflows", title: "Workflows", count: 3, atLeast: false },
      { path: "/SystemWorkflows", title: "System workflows", atLeast: false },
    ]);

    vi.mocked(console.error).mockRestore();
  });
});
