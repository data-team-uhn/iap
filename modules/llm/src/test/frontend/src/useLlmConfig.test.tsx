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

import { act, renderHook, waitFor } from "@testing-library/react";

import { jsonResponse, sentRequests, stubFailingFetch, stubFetch } from "@iap/frontend-commons/fetch.fixture";
import { CONFIG_URL, useLlmConfig } from "@iap/llm/useLlmConfig";

const catalogJson = (activeModel = "model-a") => ({
  activeProvider: "example",
  activeModel,
  providers: [
    {
      name: "example",
      label: "Example Provider",
      endpoint: "http://model-a.example.invalid/v1",
      models: [ { name: "model-a" }, { name: "other-model" } ],
    },
  ],
});

// Answers the catalog GET, and a POST with the catalog as it would be after the switch
const stubCatalog = (postStatus = 200) => stubFetch(request => request.method === "GET"
  ? jsonResponse(catalogJson())
  : jsonResponse(catalogJson(request.params.get("activeModel") ?? ""), postStatus));

const loadedHook = async () => {
  const { result } = renderHook(() => useLlmConfig());
  await waitFor(() => expect(result.current.loading).toBe(false));
  return result;
};

afterEach(() => vi.unstubAllGlobals());

describe("useLlmConfig", () => {
  it("loads the catalog on mount", async () => {
    stubCatalog();
    const result = await loadedHook();

    expect(sentRequests()[0].url).toBe(CONFIG_URL);
    expect(sentRequests()[0].method).toBe("GET");
    expect(result.current.catalog.activeProvider).toBe("example");
    expect(result.current.catalog.providers[0].models).toHaveLength(2);
    expect(result.current.loadError).toBeUndefined();
  });

  it("switches the selection as a form post", async () => {
    stubCatalog();
    const result = await loadedHook();

    await act(() => result.current.save("example", "other-model"));

    const post = sentRequests().filter(request => request.method === "POST").at(-1);
    expect(post?.url).toBe(CONFIG_URL);
    expect(post?.params.get("activeProvider")).toBe("example");
    expect(post?.params.get("activeModel")).toBe("other-model");
  });

  it("takes its new state from what the server answered, not from what was asked", async () => {
    stubCatalog();
    const result = await loadedHook();

    await act(() => result.current.save("example", "other-model"));

    expect(result.current.catalog.activeModel).toBe("other-model");
  });

  // The wording itself is requestFailure's business; what matters here is that the hook hands the
  // UI a sentence about the cause rather than the protocol
  it("reports a load failure in the user's terms", async () => {
    stubFailingFetch(500);
    const result = await loadedHook();

    expect(result.current.loadError).toContain("The server ran into a problem");
    expect(result.current.loadError).toContain("(HTTP 500)");
  });

  it("reports a catalog that arrived unreadable", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => { /* kept out of the test output */ });
    stubFetch(() => new Response("<html>Bad gateway</html>"));
    const result = await loadedHook();

    expect(result.current.loadError).toBeDefined();
    expect(result.current.catalog.providers).toEqual([]);
  });

  it("throws a refused save rather than storing it, so the screen can report the action", async () => {
    stubCatalog(403);
    const result = await loadedHook();

    await expect(result.current.save("example", "other-model")).rejects.toThrow();
    expect(result.current.loadError).toBeUndefined();
  });

  it("clears a previous load failure once a reload succeeds", async () => {
    stubFailingFetch(500);
    const result = await loadedHook();
    expect(result.current.loadError).toBeDefined();

    vi.unstubAllGlobals();
    stubCatalog();
    await act(() => result.current.reload());

    expect(result.current.loadError).toBeUndefined();
    expect(result.current.catalog.activeProvider).toBe("example");
  });
});
