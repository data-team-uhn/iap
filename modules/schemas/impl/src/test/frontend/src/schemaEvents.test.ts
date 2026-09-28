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

import { patch, sendEvent } from "@iap/schemas/schemaEvents";

const answer = (response: Partial<Response>) => vi.fn(() => Promise.resolve(response as Response));

describe("sendEvent", () => {
  it("posts the event to the node, named by its selector", async () => {
    const doFetch = answer({ ok: true, status: 200, redirected: false });

    await expect(sendEvent(doFetch, "/Schemas/study/v2", "retire")).resolves.toBeUndefined();

    expect(doFetch).toHaveBeenCalledWith("/Schemas/study/v2.retire.json",
      expect.objectContaining({ method: "POST" }));
  });

  it("resolves with what the event created", async () => {
    const doFetch = answer({ ok: true, status: 200, redirected: true, url: "http://localhost/Schemas/new" });

    await expect(sendEvent(doFetch, "/Schemas", "create", { title: "New" })).resolves.toBe("/Schemas/new");
  });

  it("passes the engine's reason for a refusal on as it stands", async () => {
    const doFetch = answer({ ok: false, status: 409, redirected: false,
      json: () => Promise.resolve({ error: "Version 2.0 is already active" }) });

    await expect(sendEvent(doFetch, "/Schemas/study/v2", "activate"))
      .rejects.toThrow("Version 2.0 is already active");
  });

  it("describes a refusal that gives no reason like any failed request", async () => {
    const doFetch = answer({
      ok: false, status: 403, redirected: false, json: () => Promise.reject(new SyntaxError()),
    });

    await expect(sendEvent(doFetch, "/Schemas", "create")).rejects.toThrow("You do not have permission");
  });

  it("describes a request that never completed", async () => {
    const doFetch = vi.fn(() => Promise.reject(new TypeError("Failed to fetch")));

    await expect(sendEvent(doFetch, "/Schemas", "create")).rejects.toThrow("could not be reached");
  });

  it("sends a patch as one JSON object", () => {
    expect(patch({ title: "New", description: null })).toEqual({ patch: "{\"title\":\"New\",\"description\":null}" });
  });
});
