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

import { DeletionRefusedError, useCategoryTree } from "@iap/categories/useCategoryTree";
import {
  jsonResponse,
  sentRequests,
  sessionInfo,
  stubFailingFetch,
  stubFetch,
} from "@iap/frontend-commons/fetch.fixture";
import { ReLoginContext } from "@iap/frontend-commons/reLogin";

// Answers the tree GET with `treeJson` and every other request with `postStatus`. `location` is the
// Location header of those answers; null for none. Only the deletion endpoint's `refusal` is read.
const stubServer = (
  treeJson: Record<string, unknown>,
  postStatus = 200,
  location: string | null = "/Categories/new-category",
  refusal?: Record<string, unknown>,
) => stubFetch(request => request.method === "GET"
  ? jsonResponse(treeJson)
  : new Response(refusal ? JSON.stringify(refusal) : null,
    { status: postStatus, headers: location ? { Location: location } : {} }));

const treeJson = {
  "jcr:primaryType": "cat:CategoriesHomepage",
  "Retrospective": {
    "jcr:primaryType": "cat:Category",
    "label": "Retrospective studies",
  },
};

const loadedHook = async () => {
  const { result } = renderHook(() => useCategoryTree());
  await waitFor(() => expect(result.current.loading).toBe(false));
  return result;
};

const lastPost = () => sentRequests().filter(request => request.method === "POST").at(-1);

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("useCategoryTree", () => {
  it("loads and parses the tree on mount", async () => {
    stubServer(treeJson);
    const result = await loadedHook();

    expect(sentRequests()[0].url).toBe("/Categories.deep.simple.json");
    expect(result.current.tree.map(node => node.label)).toEqual(["Retrospective studies"]);
    expect(result.current.loadError).toBeUndefined();
  });

  // The sentence comes from requestFailure, and a failed read must not speak of a change
  it("reports a load failure in the user's terms", async () => {
    stubFailingFetch(500);
    const result = await loadedHook();

    expect(result.current.loadError).toContain("The server ran into a problem");
    expect(result.current.loadError).toContain("(HTTP 500)");
    expect(result.current.loadError).not.toContain("change");
  });

  it("reports a tree that arrived unreadable", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => { /* kept out of the test output */ });
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response("<html>Bad gateway</html>", { status: 200 }))));
    const result = await loadedHook();

    expect(result.current.loadError).toBe("The server's response could not be read. Reload and try again.");
  });

  it("reports a server it could not reach", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))));
    const result = await loadedHook();

    expect(result.current.loadError).toContain("The server could not be reached");
    // Chrome's own wording stays off the screen
    expect(result.current.loadError).not.toContain("Failed to fetch");
  });

  it("creates a category under its parent and reloads the tree", async () => {
    stubServer(treeJson);
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
    expect(sentRequests().filter(request => request.method === "GET").length).toBe(2);
  });

  it("binds a schema version as a typed reference", async () => {
    stubServer(treeJson);
    const result = await loadedHook();

    await result.current.update("/Categories/Retrospective", { label: "Retro", schemaVersion: "uuid-sv1" });

    const post = lastPost();
    expect(post?.params.get("schemaVersion")).toBe("uuid-sv1");
    expect(post?.params.get("schemaVersion@TypeHint")).toBe("Reference");
  });

  it("removes a schema version binding explicitly", async () => {
    stubServer(treeJson);
    const result = await loadedHook();

    await result.current.update("/Categories/Retrospective", { label: "Retro", schemaVersion: null });

    const post = lastPost();
    expect(post?.params.get("schemaVersion@Delete")).toBe("true");
    expect(post?.params.has("schemaVersion")).toBe(false);
  });

  it("unbinds a schema version on its own, leaving the other fields alone", async () => {
    stubServer(treeJson);
    const result = await loadedHook();

    await result.current.unbindSchemaVersion("/Categories/Retrospective");

    const post = lastPost();
    expect(post?.params.get("schemaVersion@Delete")).toBe("true");
    expect(post?.params.has("label")).toBe(false);
    expect(post?.params.has("description")).toBe(false);
  });

  it("moves a category with a trailing-slash destination, keeping its name", async () => {
    stubServer(treeJson);
    const result = await loadedHook();

    await result.current.move("/Categories/Retrospective", "/Categories/Prospective");

    const post = lastPost();
    expect(post?.params.get(":operation")).toBe("move");
    expect(post?.params.get(":dest")).toBe("/Categories/Prospective/");
  });

  it("reorders a category with Sling's :order syntax", async () => {
    stubServer(treeJson);
    const result = await loadedHook();

    await result.current.reorder("/Categories/Retrospective", "before Prospective");

    expect(lastPost()?.params.get(":order")).toBe("before Prospective");
  });

  it("retires a category by placing the retired tag", async () => {
    stubServer(treeJson);
    const result = await loadedHook();

    await result.current.setRetired("/Categories/Retrospective", true);

    const post = lastPost();
    expect(post?.params.get("tags@Patch")).toBe("true");
    expect(post?.params.get("tags")).toBe("+retired");
  });

  it("unretires a category by removing the same tag", async () => {
    stubServer(treeJson);
    const result = await loadedHook();

    await result.current.setRetired("/Categories/Retrospective", false);

    expect(lastPost()?.params.get("tags")).toBe("-retired");
  });

  it("deletes a category through the deletion endpoint and reloads the tree", async () => {
    stubServer(treeJson);
    const result = await loadedHook();

    await act(() => result.current.remove("/Categories/Retrospective"));

    const sent = sentRequests().filter(request => request.method === "DELETE");
    expect(sent).toHaveLength(1);
    expect(sent[0].url).toBe("/Categories/Retrospective");
    expect(sentRequests().filter(request => request.method === "POST")).toHaveLength(0);
    // The reload that follows it
    expect(sentRequests().filter(request => request.method === "GET")).toHaveLength(2);
  });

  it("takes the created category's path from the Location the server reports", async () => {
    stubServer(treeJson, 200, "/Categories/Retrospective/New%20One.json");
    const result = await loadedHook();

    await act(async () => {
      expect(await result.current.create("/Categories/Retrospective", { label: "New One" }))
        .toBe("/Categories/Retrospective/New One");
    });
  });

  it("falls back to the parent when the server reports no Location", async () => {
    stubServer(treeJson, 200, null);
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

  it("passes on the reason the deletion endpoint refused", async () => {
    stubServer(treeJson, 409, null, {
      "status.code": 409,
      "status": "referenced",
      "status.message": "This item is referenced in 2 submissions (S-1, S-2).",
    });
    const result = await loadedHook();

    await expect(result.current.remove("/Categories/Retrospective"))
      .rejects.toThrow("This item is referenced in 2 submissions (S-1, S-2).");
  });

  it("still reports a refusal when its reason cannot be read", async () => {
    stubServer(treeJson, 409);
    const result = await loadedHook();

    await expect(result.current.remove("/Categories/Retrospective"))
      .rejects.toBeInstanceOf(DeletionRefusedError);
  });

  it("reports any other deletion failure as a failure", async () => {
    stubServer(treeJson, 403);
    const result = await loadedHook();

    await expect(result.current.remove("/Categories/Retrospective"))
      .rejects.not.toBeInstanceOf(DeletionRefusedError);
  });

  // Sling answers a write made with an expired session with a 500. The recovery itself is tested
  // with reLogin; this checks that a category write goes through it.
  it("recovers a write from a session that expired under it", async () => {
    let posts = 0;
    // The session is gone, so the 500 means an expired session
    stubFetch(sessionInfo("anonymous"), request => {
      if (request.method === "GET") {
        return jsonResponse(treeJson);
      }
      // The first write meets the expired session, and the re-sent one succeeds
      posts += 1;
      return posts === 1
        ? new Response(null, { status: 500 })
        : new Response(null, { headers: { Location: "/Categories/Retrospective" } });
    });
    const signIn = vi.fn(() => Promise.resolve(true));
    const { result } = renderHook(() => useCategoryTree(), {
      wrapper: ({ children }: { children: ReactNode }) =>
        <ReLoginContext value={signIn}>{children}</ReLoginContext>,
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(() => result.current.setRetired("/Categories/Retrospective", true));

    expect(signIn).toHaveBeenCalled();
    // Two POSTs for the one change: the write was sent again, not reported
    expect(sentRequests().filter(request => request.method === "POST")).toHaveLength(2);
    expect(result.current.loadError).toBeUndefined();
  });
});
