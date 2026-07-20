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

import { loadAsset } from "@iap/frontend-commons/assetManager";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

// getURLParameters is left un-mocked (it's pure, no side effects) so the `?lazy`
// detection is exercised for real; only the network-touching loadAsset is mocked.
vi.mock("@iap/frontend-commons/assetManager", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@iap/frontend-commons/assetManager")>()),
  loadAsset: vi.fn(),
}));

const mockedLoadAsset = vi.mocked(loadAsset);

const originalFetch = global.fetch;

afterEach(() => {
  global.fetch = originalFetch;
  vi.clearAllMocks();
});

function mockExtensionPointResponse(extensions: unknown[]) {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: () => Promise.resolve(extensions),
  });
}

describe("loadExtensions", () => {
  it("eagerly resolves a non-lazy asset property, storing it under the stripped key", async () => {
    mockExtensionPointResponse([
      { "iap:extensionName": "Eager", "iap:extensionRenderURL": "asset:iap-x.Eager.js" },
    ]);
    mockedLoadAsset.mockResolvedValue("EagerComponent");

    const [extension] = await loadExtensions("Views");

    expect(mockedLoadAsset).toHaveBeenCalledWith("asset:iap-x.Eager.js");
    expect(extension["iap:extensionRender"]).toBe("EagerComponent");
  });

  it("resolves an asset property marked ?lazy to a component, without fetching it yet", async () => {
    mockExtensionPointResponse([
      { "iap:extensionName": "Lazy", "iap:extensionRenderURL": "asset:iap-x.Lazy.js?lazy" },
    ]);

    const [extension] = await loadExtensions("Views");

    expect(mockedLoadAsset).not.toHaveBeenCalled();
    expect(typeof extension["iap:extensionRender"]).toBe("function");
  });

  // The resolved component's own mount-triggers-fetch behavior belongs to <LazyAsset>
  // itself (assetManager.tsx); it isn't re-verified here since it calls this file's
  // mocked `loadAsset` through assetManager's own internal closure, not through this
  // test's import binding, so mocking it from here wouldn't be observed anyway.

  it("resolves eager and lazy asset properties independently on the same extension", async () => {
    mockExtensionPointResponse([
      {
        "iap:extensionName": "Mixed",
        "iap:iconUrl": "asset:iap-x.Icon.js",
        "iap:extensionRenderURL": "asset:iap-x.Mixed.js?lazy",
      },
    ]);
    mockedLoadAsset.mockResolvedValue("IconComponent");

    const [extension] = await loadExtensions("Views");

    expect(mockedLoadAsset).toHaveBeenCalledTimes(1);
    expect(mockedLoadAsset).toHaveBeenCalledWith("asset:iap-x.Icon.js");
    expect(extension["iap:icon"]).toBe("IconComponent");
    expect(typeof extension["iap:extensionRender"]).toBe("function");
  });

  it("omits an extension whose asset fails to resolve, without affecting others", async () => {
    mockExtensionPointResponse([
      { "iap:extensionName": "Broken", "iap:extensionRenderURL": "asset:iap-x.Broken.js" },
      { "iap:extensionName": "Ok", "iap:extensionRenderURL": "asset:iap-x.Ok.js" },
    ]);
    mockedLoadAsset.mockResolvedValueOnce(null).mockResolvedValueOnce("OkComponent");

    const extensions = await loadExtensions("Views");

    expect(extensions).toHaveLength(1);
    expect(extensions[0]["iap:extensionName"]).toBe("Ok");
  });

  it("returns an empty list when the extension point itself cannot be retrieved", async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 404 });

    const extensions = await loadExtensions("Views");

    expect(extensions).toEqual([]);
  });
});
