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

import { render, screen } from "@testing-library/react";

import type { ComponentType } from "react";

import { loadAsset } from "@iap/frontend-commons/assetManager";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

// getURLParameters is left un-mocked (it's pure, no side effects) so the `?lazy`
// detection is exercised for real; the network-touching loadAsset is mocked, as is LazyAsset,
// whose own fetch-on-mount behaviour is assetManager's to verify -- here it only needs to record
// what the extension manager wraps it around.
vi.mock("@iap/frontend-commons/assetManager", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@iap/frontend-commons/assetManager")>()),
  loadAsset: vi.fn(),
  LazyAsset: ({ url, ...props }: { url: string } & Record<string, unknown>) => (
    <div data-testid="lazy-asset" data-url={url} data-props={JSON.stringify(props)} />
  ),
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

  it("defers a lazy asset to LazyAsset, passing on the URL and the render props", async () => {
    mockExtensionPointResponse([
      { "iap:extensionName": "Lazy", "iap:extensionRenderURL": "asset:iap-x.Lazy.js?lazy" },
    ]);

    const [extension] = await loadExtensions("Views");
    const Rendered = extension["iap:extensionRender"] as ComponentType<Record<string, unknown>>;
    render(<Rendered title="A lazy view" />);

    const placeholder = screen.getByTestId("lazy-asset");
    expect(placeholder).toHaveAttribute("data-url", "asset:iap-x.Lazy.js?lazy");
    expect(placeholder).toHaveAttribute("data-props", JSON.stringify({ title: "A lazy view" }));
    // Still nothing fetched: that only happens once LazyAsset itself mounts for real
    expect(mockedLoadAsset).not.toHaveBeenCalled();
  });

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

  it("takes an extension point given as a repository path as-is", async () => {
    mockExtensionPointResponse([]);

    await loadExtensions("/apps/iap/ExtensionPoints/Views");

    expect(global.fetch).toHaveBeenCalledWith("/apps/iap/ExtensionPoints/Views");
  });

  it("prefixes a bare extension point name", async () => {
    mockExtensionPointResponse([]);

    await loadExtensions("Views");

    expect(global.fetch).toHaveBeenCalledWith("/apps/iap/ExtensionPoints/Views");
  });

  it("names an unidentifiable extension 'unknown' when reporting its unresolved asset", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    mockExtensionPointResponse([{ "iap:extensionRenderURL": "asset:iap-x.Broken.js" }]);
    mockedLoadAsset.mockResolvedValue(null);

    expect(await loadExtensions("Views")).toEqual([]);

    expect(errorSpy).toHaveBeenCalledWith(
      "Skipping an extension of [Views] that failed to load.",
      expect.objectContaining({
        message: "Asset [asset:iap-x.Broken.js] for extension [unknown] resolved to nothing",
      }) as Error,
    );
    errorSpy.mockRestore();
  });
});
