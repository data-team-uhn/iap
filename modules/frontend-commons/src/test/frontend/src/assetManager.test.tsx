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

import { render, screen, waitFor } from "@testing-library/react";

// A module that really exists in the test bundle, so the dynamic import inside loadAsset is
// exercised for real. Non-"asset:" URLs are passed through untouched, which is what makes it
// reachable without having to fake a content-hashed assets.json entry.
const FIXTURE_URL = "/src/frontend-commons/remoteAsset.fixture.tsx";
// A second one, for the asset above to depend on. It has to be a genuinely different module:
// loadAsset resolves dependencies recursively with no cycle detection, so an asset that ends up
// listed as its own dependency recurses until the process dies.
const DEPENDENCY_URL = "/src/frontend-commons/remoteDependency.fixture.tsx";

// The resolved manifests and the loaded modules are memoised in module state, so every test starts
// from a fresh copy of the module.
const freshAssetManager = async () => {
  vi.resetModules();
  return import("@iap/frontend-commons/assetManager");
};

// Serves the two manifests, and 404s anything else
const stubManifests = (
  assets: Record<string, string> = {},
  dependencies: Record<string, string[]> | null = {},
) => {
  const fetchMock = vi.fn((url: RequestInfo | URL) => {
    const target = String(url);
    if (target.endsWith("assets.json")) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve(assets) } as unknown as Response);
    }
    if (target.endsWith("assetDependencies.json")) {
      return Promise.resolve((dependencies
        ? { ok: true, json: () => Promise.resolve(dependencies) }
        : { ok: false, status: 404 }) as unknown as Response);
    }
    return Promise.resolve({ ok: false, status: 404 } as Response);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

describe("assetManager", () => {
  let errorSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  describe("getURLParameters", () => {
    it("reads the parameters off an asset URL", async () => {
      const { getURLParameters } = await freshAssetManager();

      const parameters = getURLParameters("asset:iap-test.Widget.js?component=Widget&lazy");
      expect(parameters.get("component")).toBe("Widget");
      expect(parameters.has("lazy")).toBe(true);
    });

    it("is empty for a URL without parameters", async () => {
      const { getURLParameters } = await freshAssetManager();

      expect([...getURLParameters("asset:iap-test.Widget.js")]).toEqual([]);
    });
  });

  describe("getAssetURL", () => {
    it("passes a plain URL through untouched", async () => {
      const { getAssetURL } = await freshAssetManager();

      expect(await getAssetURL("/libs/iap/resources/thing.js")).toBe("/libs/iap/resources/thing.js");
    });

    it("resolves an asset name to its content-hashed path", async () => {
      stubManifests({ "iap-test.Widget.js": "iap-test.Widget.abc123.js" });
      const { getAssetURL } = await freshAssetManager();

      expect(await getAssetURL("asset:iap-test.Widget.js")).toBe("/libs/iap/resources/iap-test.Widget.abc123.js");
    });

    it("ignores the query parameters when looking the asset up", async () => {
      stubManifests({ "iap-test.Widget.js": "iap-test.Widget.abc123.js" });
      const { getAssetURL } = await freshAssetManager();

      expect(await getAssetURL("asset:iap-test.Widget.js?component=Widget"))
        .toBe("/libs/iap/resources/iap-test.Widget.abc123.js");
    });

    it("reports an asset the manifest does not know about", async () => {
      stubManifests({});
      const { getAssetURL } = await freshAssetManager();

      expect(await getAssetURL("asset:iap-test.Missing.js")).toBe("");
      expect(errorSpy).toHaveBeenCalledWith("Unknown asset asset:iap-test.Missing.js");
    });

    it("reports a manifest that cannot be fetched, and retries next time", async () => {
      const fetchMock = vi.fn((_url: RequestInfo | URL) => Promise.resolve({ ok: false, status: 500 } as Response));
      vi.stubGlobal("fetch", fetchMock);
      const { getAssetURL } = await freshAssetManager();

      expect(await getAssetURL("asset:iap-test.Widget.js")).toBe("");
      expect(errorSpy).toHaveBeenCalledWith("Failed to resolve assets", expect.any(Error));

      await getAssetURL("asset:iap-test.Widget.js");
      // Nothing was cached, so the manifest is asked for again
      expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith("assets.json"))).toHaveLength(2);
    });

    it("fetches the manifest only once for concurrent lookups", async () => {
      const fetchMock = stubManifests({ "a.js": "a.1.js", "b.js": "b.1.js" });
      const { getAssetURL } = await freshAssetManager();

      await Promise.all([getAssetURL("asset:a.js"), getAssetURL("asset:b.js")]);

      expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith("assets.json"))).toHaveLength(1);
    });
  });

  describe("loadAsset", () => {
    it("loads the default export of the module behind the asset", async () => {
      stubManifests();
      const { loadAsset } = await freshAssetManager();

      const component = await loadAsset(FIXTURE_URL);

      expect((component as { name: string }).name).toBe("RemoteDefault");
    });

    it("loads the export named by the component parameter", async () => {
      stubManifests();
      const { loadAsset } = await freshAssetManager();

      const component = await loadAsset(`${FIXTURE_URL}?component=RemoteNamed`);

      expect((component as { name: string }).name).toBe("RemoteNamed");
    });

    it("loads each asset once and reuses it", async () => {
      stubManifests();
      const { loadAsset } = await freshAssetManager();

      const first = await loadAsset(FIXTURE_URL);
      const second = await loadAsset(FIXTURE_URL);

      expect(second).toBe(first);
    });

    it("loads an asset's declared dependencies before the asset itself", async () => {
      delete (globalThis as { __remoteDependencyLoaded?: boolean }).__remoteDependencyLoaded;
      stubManifests({}, { [FIXTURE_URL]: [DEPENDENCY_URL] });
      const { loadAsset } = await freshAssetManager();

      const component = await loadAsset(FIXTURE_URL);

      expect((component as { name: string }).name).toBe("RemoteDefault");
      // Evaluating the dependency's module is what sets this
      expect((globalThis as { __remoteDependencyLoaded?: boolean }).__remoteDependencyLoaded).toBe(true);
    });

    it("reports an asset whose module cannot be resolved", async () => {
      stubManifests({});
      const { loadAsset } = await freshAssetManager();

      expect(await loadAsset("asset:iap-test.Missing.js")).toBeNull();
      expect(errorSpy).toHaveBeenCalledWith("Failed to load module asset:iap-test.Missing.js");
    });

    it("skips the relogin dialog in a production build, where it is already bundled", async () => {
      vi.stubEnv("NODE_ENV", "production");
      const fetchMock = stubManifests();
      const { loadAsset } = await freshAssetManager();

      expect(await loadAsset("asset:iap-login.ReLoginDialog.js")).toBeUndefined();
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it("still loads other assets in a production build", async () => {
      vi.stubEnv("NODE_ENV", "production");
      stubManifests();
      const { loadAsset } = await freshAssetManager();

      expect(await loadAsset(FIXTURE_URL)).toBeDefined();
    });
  });

  describe("LazyAsset", () => {
    it("renders nothing until the asset has loaded, then the component", async () => {
      stubManifests();
      const { LazyAsset } = await freshAssetManager();

      const { container } = render(<LazyAsset url={FIXTURE_URL} label="Loaded lazily" />);
      expect(container).toBeEmptyDOMElement();

      expect(await screen.findByTestId("remote-default")).toHaveTextContent("Loaded lazily");
    });

    it("forwards its other props to the loaded component", async () => {
      stubManifests();
      const { LazyAsset } = await freshAssetManager();

      render(<LazyAsset url={`${FIXTURE_URL}?component=RemoteNamed`} label="Named and lazy" />);

      expect(await screen.findByTestId("remote-named")).toHaveTextContent("Named and lazy");
    });

    it("reports an asset that will not load", async () => {
      stubManifests();
      const { LazyAsset } = await freshAssetManager();

      render(<LazyAsset url="/src/frontend-commons/doesNotExist.tsx" />);

      await waitFor(() => {
        expect(errorSpy).toHaveBeenCalledWith(
          "Something went wrong loading the asset [/src/frontend-commons/doesNotExist.tsx]",
          expect.anything(),
        );
      });
    });

    it("drops a result that arrives after it has been unmounted", async () => {
      stubManifests();
      const { LazyAsset } = await freshAssetManager();

      const { unmount } = render(<LazyAsset url={FIXTURE_URL} />);
      unmount();

      // Nothing was rendered, and React was not asked to update an unmounted component
      await waitFor(() => { expect(screen.queryByTestId("remote-default")).not.toBeInTheDocument(); });
    });
  });

  it("treats a missing asset dependencies manifest as empty, fetching it only once", async () => {
    const fetchMock = stubManifests({}, null);
    const { loadAsset } = await freshAssetManager();

    await loadAsset("asset:iap-test.First.js");
    await loadAsset("asset:iap-test.Second.js");

    // The 404 was remembered as "no dependencies" instead of being re-fetched per asset
    const dependencyFetches = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("assetDependencies.json"));
    expect(dependencyFetches).toHaveLength(1);
  });
});
