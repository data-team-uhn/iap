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

import { forgetWorkflowHomepages, loadWorkflowHomepages } from "@iap/workflows/useWorkflowHomepages";

type FetchStub = (url: string) => Promise<Response>;

const okResponse = (body: unknown) =>
  ({ ok: true, status: 200, json: () => Promise.resolve(body) } as unknown as Response);

const answering = (body: unknown) => vi.fn<FetchStub>(() => Promise.resolve(okResponse(body)));

describe("loadWorkflowHomepages", () => {
  // The discovery is kept for the life of the session, so each test starts from an unasked one
  beforeEach(forgetWorkflowHomepages);

  it("keeps what it discovered, since every console URL is read against it", async () => {
    // Asked on every navigation rather than once per page, and answered by a tree that changes only
    // when a bundle is installed: asking again on each would be a request per click for nothing
    const fetchUtil = answering({ homepages: [ { path: "/Workflows", title: "Workflows" } ] });

    await loadWorkflowHomepages(fetchUtil);
    await loadWorkflowHomepages(fetchUtil);

    expect(fetchUtil).toHaveBeenCalledTimes(1);
  });

  it("shares one request between callers that ask before it lands", async () => {
    const fetchUtil = answering({ homepages: [ { path: "/Workflows", title: "Workflows" } ] });

    await Promise.all([ loadWorkflowHomepages(fetchUtil), loadWorkflowHomepages(fetchUtil) ]);

    expect(fetchUtil).toHaveBeenCalledTimes(1);
  });

  it("asks the canonical homepage which homepages hold workflows", async () => {
    const fetchUtil = answering({
      homepages: [
        { path: "/Workflows", title: "Workflows" },
        { path: "/SystemWorkflows", title: "System workflows" },
      ],
    });

    const homepages = await loadWorkflowHomepages(fetchUtil);

    expect(fetchUtil).toHaveBeenCalledWith("/Workflows.homepages.json");
    expect(homepages).toEqual([
      { path: "/Workflows", title: "Workflows" },
      { path: "/SystemWorkflows", title: "System workflows" },
    ]);
  });

  it("skips entries with nothing usable in them", async () => {
    const fetchUtil = answering({
      homepages: [ { title: "Nowhere" }, "not a homepage", null, { path: "/Workflows", title: "Workflows" } ],
    });

    await expect(loadWorkflowHomepages(fetchUtil)).resolves.toEqual([ { path: "/Workflows", title: "Workflows" } ]);
  });

  it("falls back to the homepage everybody has when the discovery cannot be made", async () => {
    // A deployment whose endpoint is missing, or a refusal: listing the workflows everyone can see
    // beats listing none at all
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const fetchUtil = vi.fn<FetchStub>(() =>
      Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) } as unknown as Response));

    await expect(loadWorkflowHomepages(fetchUtil)).resolves.toEqual([ { path: "/Workflows", title: "Workflows" } ]);

    vi.mocked(console.error).mockRestore();
  });

  it("answers with no homepages when the server says there are none", async () => {
    const fetchUtil = answering({ homepages: [] });

    await expect(loadWorkflowHomepages(fetchUtil)).resolves.toEqual([]);
  });

  it("answers with no homepages when the answer holds no list of them", async () => {
    // An answer in a shape this reader does not know is an answer it can make nothing of, which is
    // not the same as an unreachable endpoint: there is nothing to fall back to
    await expect(loadWorkflowHomepages(answering({}))).resolves.toEqual([]);
    await expect(loadWorkflowHomepages(answering({ homepages: "/Workflows" }))).resolves.toEqual([]);
  });
});
