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

import { CategoryReferencedError, useCategoryTree } from "@iap/categories/useCategoryTree";

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
        ok: true, status: 200, statusText: "OK",
        json: () => Promise.resolve(treeJson),
      } as unknown as Response);
    }
    return Promise.resolve({
      ok: postStatus < 400,
      status: postStatus,
      statusText: postStatus === 200 ? "OK" : "Error",
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

  it("reports a load failure", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
      ok: false, status: 500, statusText: "Server Error",
    } as unknown as Response)));
    const result = await loadedHook();

    expect(result.current.loadError).toContain("500");
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

    expect(result.current.loadError).toBe("connection reset");
  });

  it("translates a 409 deletion refusal into a CategoryReferencedError", async () => {
    stubFetch(treeJson, 409);
    const result = await loadedHook();

    await expect(result.current.remove("/Categories/Retrospective"))
      .rejects.toBeInstanceOf(CategoryReferencedError);
    expect(lastPost()?.params.get(":operation")).toBe("delete");
  });
});
