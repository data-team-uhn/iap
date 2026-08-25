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

import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import { CONFIG_URL, useLlmConfig } from "@iap/llm/useLlmConfig";

// Every request the stubbed fetch has served, for asserting on the wire protocol.
interface RecordedRequest {
  url: string;
  method: string;
  params: URLSearchParams;
}

let requests: RecordedRequest[] = [];

const catalogJson = (activeModel = "llama3.2-3b") => ({
  activeProvider: "local",
  activeModel,
  providers: [
    {
      name: "local",
      label: "Local (Ollama)",
      endpoint: "http://localhost:11434/v1",
      models: [ { name: "llama3.2-3b" }, { name: "other-model" } ],
    },
  ],
});

// Answers the catalog GET, and a POST with the catalog as it would be after the switch. Every answer
// carries the `url` it came back from: the requests go through useAuthenticatedFetch, which reads it
// to tell an ordinary response from the login page Sling redirects to when the session has expired.
const stubFetch = (postStatus = 200) =>
  vi.stubGlobal("fetch", vi.fn((url: string, options?: RequestInit) => {
    const method = options?.method ?? "GET";
    const params = new URLSearchParams(options?.body as URLSearchParams | undefined);
    requests.push({ url, method, params });
    if (method === "GET") {
      return Promise.resolve({
        ok: true, status: 200, statusText: "OK", url,
        json: () => Promise.resolve(catalogJson()),
      } as unknown as Response);
    }
    return Promise.resolve({
      ok: postStatus < 400,
      status: postStatus,
      statusText: postStatus === 200 ? "OK" : "Error",
      url,
      json: () => Promise.resolve(catalogJson(params.get("activeModel") ?? "")),
    } as unknown as Response);
  }));

// A server that answers everything with the same failure, while reporting the session as live: what
// makes the failure the server's own rather than a lapsed session to be recovered from.
const stubFailingFetch = (status: number, statusText: string) =>
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve((url === SESSION_INFO_URL
    ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
    : { ok: false, status, statusText, url }) as unknown as Response)));

const loadedHook = async () => {
  const { result } = renderHook(() => useLlmConfig());
  await waitFor(() => expect(result.current.loading).toBe(false));
  return result;
};

beforeEach(() => {
  requests = [];
});

afterEach(() => vi.unstubAllGlobals());

describe("useLlmConfig", () => {
  it("loads the catalog on mount", async () => {
    stubFetch();
    const result = await loadedHook();

    expect(requests[0].url).toBe(CONFIG_URL);
    expect(requests[0].method).toBe("GET");
    expect(result.current.catalog.activeProvider).toBe("local");
    expect(result.current.catalog.providers[0].models).toHaveLength(2);
    expect(result.current.loadError).toBeUndefined();
  });

  it("switches the selection as a form post", async () => {
    stubFetch();
    const result = await loadedHook();

    await act(() => result.current.save("local", "other-model"));

    const post = requests.filter(request => request.method === "POST").at(-1);
    expect(post?.url).toBe(CONFIG_URL);
    expect(post?.params.get("activeProvider")).toBe("local");
    expect(post?.params.get("activeModel")).toBe("other-model");
  });

  it("takes its new state from what the server answered, not from what was asked", async () => {
    stubFetch();
    const result = await loadedHook();

    await act(() => result.current.save("local", "other-model"));

    expect(result.current.catalog.activeModel).toBe("other-model");
  });

  // The wording itself is requestFailure's business; what matters here is that the hook hands the
  // UI a sentence about the cause rather than the protocol
  it("reports a load failure in the user's terms", async () => {
    stubFailingFetch(500, "Server Error");
    const result = await loadedHook();

    expect(result.current.loadError).toContain("The server ran into a problem");
    expect(result.current.loadError).toContain("(HTTP 500)");
  });

  it("reports a catalog that arrived unreadable", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve({
      ok: true, status: 200, statusText: "OK", url,
      json: () => Promise.reject(new Error("Unexpected token")),
    } as unknown as Response)));
    const result = await loadedHook();

    expect(result.current.loadError).toBeDefined();
    expect(result.current.catalog.providers).toEqual([]);
  });

  it("throws a refused save rather than storing it, so the screen can report the action", async () => {
    stubFetch(403);
    const result = await loadedHook();

    await expect(result.current.save("local", "other-model")).rejects.toThrow();
    expect(result.current.loadError).toBeUndefined();
  });

  it("clears a previous load failure once a reload succeeds", async () => {
    stubFailingFetch(500, "Server Error");
    const result = await loadedHook();
    expect(result.current.loadError).toBeDefined();

    vi.unstubAllGlobals();
    stubFetch();
    await act(() => result.current.reload());

    expect(result.current.loadError).toBeUndefined();
    expect(result.current.catalog.activeProvider).toBe("local");
  });
});
