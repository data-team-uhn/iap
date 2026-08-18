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
import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";

const jsonResponse = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" }
});

// The session-aware fetch the caller supplies. Taking it as an argument is what keeps this module
// free of both React and globals, so these tests drive it directly rather than patching `fetch`.
const answering = (response: Response) =>
  vi.fn<AuthenticatedFetch>().mockResolvedValue(response);

describe("requestDeletion", () => {
  it("sends a bare DELETE when no options are given", async () => {
    const fetchMock = answering(jsonResponse(200, { "status.code": 200, status: "archived" }));

    const outcome = await requestDeletion(fetchMock, "/content/victim");

    expect(outcome.status).toBe("archived");
    const [ url, init ] = fetchMock.mock.calls[0];
    expect(new URL(url).pathname).toBe("/content/victim");
    expect(new URL(url).search).toBe("");
    expect(init).toEqual(expect.objectContaining({ method: "DELETE" }));
  });

  it("passes only the options that are turned on", async () => {
    const fetchMock = answering(
      jsonResponse(200, { "status.code": 200, status: "dryRun", executable: true }));

    await requestDeletion(fetchMock, "/content/victim",
      { dryRun: true, permanent: true, recursive: false });

    const url = new URL(fetchMock.mock.calls[0][0]);
    expect(url.searchParams.get("dryRun")).toBe("true");
    expect(url.searchParams.get("permanent")).toBe("true");
    expect(url.searchParams.has("recursive")).toBe(false);
  });

  it("returns a refusal as an outcome rather than throwing", async () => {
    const outcome = await requestDeletion(answering(jsonResponse(409, {
      "status.code": 409,
      status: "referenced",
      "status.message": "This item is referenced by 2 submissions.",
      referrers: [ { type: "sub:Submission", label: "submission", count: 2, names: [ "S-1", "S-2" ] } ],
      inaccessibleReferrers: 0
    })), "/content/victim");

    expect(outcome.status).toBe("referenced");
    expect(outcome.referrers?.[0].count).toBe(2);
  });

  it("reports an unreadable body as a failure instead of crashing", async () => {
    const outcome = await requestDeletion(
      answering(new Response("<html>Server Error</html>", { status: 500 })), "/content/victim");

    expect(outcome.status).toBe("failed");
    expect(outcome["status.message"]).toContain("500");
  });

  it("reports an unreadable 404 as a missing resource", async () => {
    const outcome = await requestDeletion(
      answering(new Response("<html>Not Found</html>", { status: 404 })), "/content/gone");

    expect(outcome.status).toBe("missing");
  });

  it("does not trust a success it cannot read", async () => {
    // A 200 whose body is not the endpoint's own JSON means something answered in its place, so
    // the deletion cannot be reported as done
    const outcome = await requestDeletion(
      answering(jsonResponse(200, { unexpected: true })), "/content/victim");

    expect(outcome.status).toBe("failed");
  });

  // An expired session the user declined to sign back in for surfaces as a rejection from the
  // supplied fetch; it is not an outcome the endpoint gave, so it must not be reported as one.
  it("lets a failure from the supplied fetch reject", async () => {
    const failing = vi.fn<AuthenticatedFetch>()
      .mockRejectedValue(new Error("Not authenticated, and signing in was abandoned"));

    await expect(requestDeletion(failing, "/content/victim")).rejects.toThrow("abandoned");
  });
});
