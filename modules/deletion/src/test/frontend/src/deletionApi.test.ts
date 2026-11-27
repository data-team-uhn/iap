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

import { examineDeletion } from "@iap/deletion/deletionApi";
import { jsonResponse } from "@iap/frontend-commons/fetch.fixture";
import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { UNREADABLE_RESPONSE } from "@iap/frontend-commons/requestFailure";

// A stand-in for the fetch the caller passes in.
const answering = (response: Response) =>
  vi.fn<AuthenticatedFetch>().mockResolvedValue(response);

describe("examineDeletion", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("reads what deleting a resource would do, changing nothing", async () => {
    const fetchMock = answering(jsonResponse({ "status.code": 200, status: "dryRun", executable: true }));

    const outcome = await examineDeletion(fetchMock, "/content/victim");

    expect(outcome.executable).toBe(true);
    const [ url, init ] = fetchMock.mock.calls[0];
    expect(url).toBe("/content/victim.deletion.json");
    expect(init).toBeUndefined();
  });

  it("returns what blocks the deletion as an outcome", async () => {
    const outcome = await examineDeletion(answering(jsonResponse({
      "status.code": 200,
      status: "dryRun",
      executable: false,
      "status.message": "This item is referenced by 2 submissions.",
      referrers: [ { type: "sub:Submission", label: "submission", count: 2, names: [ "S-1", "S-2" ] } ],
      inaccessibleReferrers: 0
    })), "/content/victim");

    expect(outcome.executable).toBe(false);
    expect(outcome.referrers?.[0].count).toBe(2);
  });

  it("reports an unreadable body as a failure instead of crashing, logging its status", async () => {
    const warned = vi.spyOn(console, "warn").mockImplementation(() => { /* kept out of the test output */ });

    const outcome = await examineDeletion(
      answering(new Response("<html>Server Error</html>", { status: 500 })), "/content/victim");

    expect(outcome).toEqual({ "status.code": 500, status: "failed", "status.message": UNREADABLE_RESPONSE });
    expect(warned).toHaveBeenCalledWith(expect.stringContaining("HTTP 500"), expect.anything());
  });

  it("reports an unreadable 404 as a missing resource", async () => {
    const outcome = await examineDeletion(
      answering(new Response("<html>Not Found</html>", { status: 404 })), "/content/gone");

    expect(outcome.status).toBe("missing");
  });

  it("does not trust an answer it cannot read", async () => {
    // A 200 whose body is not the endpoint's own JSON means something answered in its place
    const outcome = await examineDeletion(answering(jsonResponse({ unexpected: true })), "/content/victim");

    expect(outcome.status).toBe("failed");
  });

  // The fetch rejects when the user declines to sign back in. The endpoint gave no outcome, so none is
  // reported.
  it("lets a failure from the supplied fetch reject", async () => {
    const failing = vi.fn<AuthenticatedFetch>()
      .mockRejectedValue(new Error("Not authenticated, and signing in was abandoned"));

    await expect(examineDeletion(failing, "/content/victim")).rejects.toThrow("abandoned");
  });
});
