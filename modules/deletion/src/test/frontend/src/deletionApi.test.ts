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

import { requestDeletion } from "@iap/deletion/deletionApi";

const jsonResponse = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" }
});

describe("requestDeletion", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("sends a bare DELETE when no options are given", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(200, { "status.code": 200, status: "archived" }));

    const outcome = await requestDeletion("/content/victim");

    expect(outcome.status).toBe("archived");
    const [ url, init ] = fetchMock.mock.calls[0];
    expect((url as URL).pathname).toBe("/content/victim");
    expect((url as URL).search).toBe("");
    expect(init).toEqual(expect.objectContaining({ method: "DELETE" }));
  });

  it("passes only the options that are turned on", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(200, { "status.code": 200, status: "dryRun", executable: true }));

    await requestDeletion("/content/victim", { dryRun: true, permanent: true, recursive: false });

    const url = fetchMock.mock.calls[0][0] as URL;
    expect(url.searchParams.get("dryRun")).toBe("true");
    expect(url.searchParams.get("permanent")).toBe("true");
    expect(url.searchParams.has("recursive")).toBe(false);
  });

  it("returns a refusal as an outcome rather than throwing", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(409, {
      "status.code": 409,
      status: "referenced",
      "status.message": "This item is referenced by 2 submissions.",
      referrers: [ { type: "sub:Submission", label: "submission", count: 2, names: [ "S-1", "S-2" ] } ],
      inaccessibleReferrers: 0
    }));

    const outcome = await requestDeletion("/content/victim");

    expect(outcome.status).toBe("referenced");
    expect(outcome.referrers?.[0].count).toBe(2);
  });

  it("reports an unreadable body as a failure instead of crashing", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("<html>Server Error</html>", { status: 500 }));

    const outcome = await requestDeletion("/content/victim");

    expect(outcome.status).toBe("failed");
    expect(outcome["status.message"]).toContain("500");
  });

  it("reports an unreadable 404 as a missing resource", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("<html>Not Found</html>", { status: 404 }));

    expect((await requestDeletion("/content/gone")).status).toBe("missing");
  });

  it("does not trust a success it cannot read", async () => {
    // A 200 whose body is not the endpoint's own JSON means something answered in its place, so
    // the deletion cannot be reported as done
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(200, { unexpected: true }));

    expect((await requestDeletion("/content/victim")).status).toBe("failed");
  });
});
