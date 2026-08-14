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

import type { ReactNode } from "react";

import { act, renderHook, waitFor } from "@testing-library/react";

import { CategoryReferencedError, useCategoryTree } from "@iap/categories/useCategoryTree";
import { ReLoginContext, SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";

// Every request the stubbed fetch has served, for asserting on the wire protocol.
interface RecordedRequest {
  url: string;
  method: string;
  params: URLSearchParams;
}

let requests: RecordedRequest[] = [];

// Stubs fetch to answer the tree GET with the given JSON and every POST with a success (or the
// given status), recording all requests.
// `location` is what the POST responses report as their Location header; null stands for a server
// that reports none.
// Every answer carries the `url` it came back from: the requests go through useAuthenticatedFetch,
// which reads it to tell an ordinary response from the login page Sling redirects to when the
// session has expired.
const stubFetch = (
  treeJson: Record<string, unknown>,
  postStatus = 200,
  location: string | null = "/Categories/new-category",
) =>
  vi.stubGlobal("fetch", vi.fn((url: string, options?: RequestInit) => {
    const method = options?.method ?? "GET";
    requests.push({
      url,
      method,
      params: new URLSearchParams(options?.body as URLSearchParams | undefined),
    });
    if (method === "GET") {
      return Promise.resolve({
        ok: true, status: 200, statusText: "OK", url,
        json: () => Promise.resolve(treeJson),
      } as unknown as Response);
    }
    return Promise.resolve({
      ok: postStatus < 400,
      status: postStatus,
      statusText: postStatus === 200 ? "OK" : "Error",
      url,
      headers: { get: (name: string) => name === "Location" ? location : null },
    } as unknown as Response);
  }));

const treeJson = {
  "jcr:primaryType": "cat:CategoriesHomepage",
  "Retrospective": {
    "jcr:primaryType": "cat:Category",
    "label": "Retrospective studies",
    "retired": false,
  },
};

const loadedHook = async () => {
  const { result } = renderHook(() => useCategoryTree());
  await waitFor(() => expect(result.current.loading).toBe(false));
  return result;
};

const lastPost = () => requests.filter(request => request.method === "POST").at(-1);

beforeEach(() => {
  requests = [];
});

afterEach(() => vi.unstubAllGlobals());

describe("useCategoryTree", () => {
  it("loads and parses the tree on mount", async () => {
    stubFetch(treeJson);
    const result = await loadedHook();

    expect(requests[0].url).toBe("/Categories.deep.json");
    expect(result.current.tree.map(node => node.label)).toEqual(["Retrospective studies"]);
    expect(result.current.loadError).toBeUndefined();
  });

  // The wording itself is requestFailure's business; what matters here is that the hook hands the
  // UI a sentence about the cause rather than the protocol, and never one about "the change" when
  // all it did was read
  it("reports a load failure in the user's terms", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve((url === SESSION_INFO_URL
      // The session is still there, which is what makes this 500 the server's own problem rather
      // than a lapsed session to sign back in for
      ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
      : { ok: false, status: 500, statusText: "Server Error", url }) as unknown as Response)));
    const result = await loadedHook();

    expect(result.current.loadError).toContain("The server ran into a problem");
    expect(result.current.loadError).toContain("(HTTP 500)");
    expect(result.current.loadError).not.toContain("change");
  });

  it("reports a tree that arrived unreadable", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve({
      ok: true, status: 200, statusText: "OK", url,
      json: () => Promise.reject(new SyntaxError("Unexpected token < in JSON at position 0")),
    } as unknown as Response)));
    const result = await loadedHook();

    expect(result.current.loadError).toBe("The server's response could not be read.");
  });

  it("reports a server it could not reach", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))));
    const result = await loadedHook();

    expect(result.current.loadError).toContain("The server could not be reached");
    // Chrome's phrasing for it, which means nothing to a user, does not reach the screen
    expect(result.current.loadError).not.toContain("Failed to fetch");
  });

  it("creates a category under its parent and reloads the tree", async () => {
    stubFetch(treeJson);
    const result = await loadedHook();

    const newPath = await result.current.create("/Categories/Retrospective",
      { label: "New one", description: "Something" });

    const post = lastPost();
    expect(post?.url).toBe("/Categories/Retrospective/");
    expect(post?.params.get("jcr:primaryType")).toBe("cat:Category");
    expect(post?.params.get(":nameHint")).toBe("New one");
    expect(post?.params.get("label")).toBe("New one");
    expect(post?.params.get("description")).toBe("Something");
    expect(newPath).toBe("/Categories/new-category");
    // The mount GET, then a re-fetch after the mutation
    expect(requests.filter(request => request.method === "GET").length).toBe(2);
  });

  it("binds a schema version as a typed reference", async () => {
    stubFetch(treeJson);
    const result = await loadedHook();

    await result.current.update("/Categories/Retrospective", { label: "Retro", schemaVersion: "uuid-sv1" });

    const post = lastPost();
    expect(post?.params.get("schemaVersion")).toBe("uuid-sv1");
    expect(post?.params.get("schemaVersion@TypeHint")).toBe("Reference");
  });

  it("removes a schema version binding explicitly", async () => {
    stubFetch(treeJson);
    const result = await loadedHook();

    await result.current.update("/Categories/Retrospective", { label: "Retro", schemaVersion: null });

    const post = lastPost();
    expect(post?.params.get("schemaVersion@Delete")).toBe("true");
    expect(post?.params.has("schemaVersion")).toBe(false);
  });

  it("unbinds a schema version on its own, leaving the other fields alone", async () => {
    stubFetch(treeJson);
    const result = await loadedHook();

    await result.current.unbindSchemaVersion("/Categories/Retrospective");

    const post = lastPost();
    expect(post?.params.get("schemaVersion@Delete")).toBe("true");
    expect(post?.params.has("label")).toBe(false);
    expect(post?.params.has("description")).toBe(false);
  });

  it("moves a category with a trailing-slash destination, keeping its name", async () => {
    stubFetch(treeJson);
    const result = await loadedHook();

    await result.current.move("/Categories/Retrospective", "/Categories/Prospective");

    const post = lastPost();
    expect(post?.params.get(":operation")).toBe("move");
    expect(post?.params.get(":dest")).toBe("/Categories/Prospective/");
  });

  it("reorders a category with Sling's :order syntax", async () => {
    stubFetch(treeJson);
    const result = await loadedHook();

    await result.current.reorder("/Categories/Retrospective", "before Prospective");

    expect(lastPost()?.params.get(":order")).toBe("before Prospective");
  });

  it("retires a category as a typed boolean", async () => {
    stubFetch(treeJson);
    const result = await loadedHook();

    await result.current.setRetired("/Categories/Retrospective", true);

    const post = lastPost();
    expect(post?.params.get("retired")).toBe("true");
    expect(post?.params.get("retired@TypeHint")).toBe("Boolean");
  });

  it("deletes a category and reloads the tree", async () => {
    stubFetch(treeJson);
    const result = await loadedHook();

    await act(() => result.current.remove("/Categories/Retrospective"));

    expect(lastPost()?.params.get(":operation")).toBe("delete");
    // The reload that follows it
    expect(requests.filter(request => request.method === "GET")).toHaveLength(2);
  });

  it("takes the created category's path from the Location the server reports", async () => {
    stubFetch(treeJson, 200, "/Categories/Retrospective/New%20One.json");
    const result = await loadedHook();

    await act(async () => {
      expect(await result.current.create("/Categories/Retrospective", { label: "New One" }))
        .toBe("/Categories/Retrospective/New One");
    });
  });

  it("falls back to the parent when the server reports no Location", async () => {
    stubFetch(treeJson, 200, null);
    const result = await loadedHook();

    await act(async () => {
      expect(await result.current.create("/Categories/Retrospective", { label: "New One" }))
        .toBe("/Categories/Retrospective");
    });
  });

  it("reports a load failure that was not an Error", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject("connection reset")));
    const result = await loadedHook();

    expect(result.current.loadError).toBe("Something went wrong: connection reset");
  });

  it("translates a 409 deletion refusal into a CategoryReferencedError", async () => {
    stubFetch(treeJson, 409);
    const result = await loadedHook();

    await expect(result.current.remove("/Categories/Retrospective"))
      .rejects.toBeInstanceOf(CategoryReferencedError);
    expect(lastPost()?.params.get(":operation")).toBe("delete");
  });

  // Why these requests go through useAuthenticatedFetch: Sling answers a *write* with an expired
  // session with a 500, so a session that lapsed while the manager was open would otherwise be
  // described as "the server ran into a problem" and the change lost. The recovery itself is
  // reLogin.test.tsx's business; what matters here is that a category write takes part in it.
  it("recovers a write from a session that expired under it", async () => {
    let posts = 0;
    vi.stubGlobal("fetch", vi.fn((url: string, options?: RequestInit) => {
      const method = options?.method ?? "GET";
      requests.push({
        url,
        method,
        params: new URLSearchParams(options?.body as URLSearchParams | undefined),
      });
      if (url === SESSION_INFO_URL) {
        // The session really is gone, which is what tells this 500 apart from a server failure
        return Promise.resolve({
          ok: true, status: 200, url,
          json: () => Promise.resolve({ userID: "anonymous" }),
        } as unknown as Response);
      }
      if (method === "GET") {
        return Promise.resolve({
          ok: true, status: 200, url, json: () => Promise.resolve(treeJson),
        } as unknown as Response);
      }
      // The first write is the one that ran into the expired session; the re-sent one succeeds
      posts += 1;
      return Promise.resolve(posts === 1
        ? { ok: false, status: 500, url } as unknown as Response
        : {
          ok: true, status: 200, url,
          headers: { get: () => "/Categories/Retrospective" },
        } as unknown as Response);
    }));
    const signIn = vi.fn(() => Promise.resolve(true));
    const { result } = renderHook(() => useCategoryTree(), {
      wrapper: ({ children }: { children: ReactNode }) =>
        <ReLoginContext value={signIn}>{children}</ReLoginContext>,
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(() => result.current.setRetired("/Categories/Retrospective", true));

    expect(signIn).toHaveBeenCalled();
    // Re-sent rather than reported: two POSTs for the one change the user asked for
    expect(requests.filter(request => request.method === "POST")).toHaveLength(2);
    expect(result.current.loadError).toBeUndefined();
  });
});
