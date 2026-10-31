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

import { renderHook, waitFor } from "@testing-library/react";

import { createExtensionLoader } from "@iap/ui-extension/extensionLoader";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(() => Promise.resolve([])),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

const POINT = "TestComponent";

// Each case makes a loader of its own, since a loader keeps its result for the life of the page
describe("createExtensionLoader", () => {
  afterEach(() => {
    vi.clearAllMocks();
    vi.restoreAllMocks();
  });

  it("loads the extensions of its point", async () => {
    await createExtensionLoader(POINT, "test components").load();

    expect(mockedLoadExtensions).toHaveBeenCalledWith(POINT);
  });

  it("loads them once however many times it is asked", async () => {
    const loader = createExtensionLoader(POINT, "test components");

    await loader.load();
    await loader.load();

    expect(mockedLoadExtensions).toHaveBeenCalledTimes(1);
  });

  it("shares one load between the callers asking while it is on its way", async () => {
    const loader = createExtensionLoader(POINT, "test components");

    await Promise.all([ loader.load(), loader.load() ]);

    expect(mockedLoadExtensions).toHaveBeenCalledTimes(1);
  });

  it("logs a failed load instead of failing, and tries again when next asked", async () => {
    const logged = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const failure = new Error("the point could not be read");
    mockedLoadExtensions.mockRejectedValueOnce(failure);
    const loader = createExtensionLoader(POINT, "test components");

    await expect(loader.load()).resolves.toBeUndefined();
    expect(logged).toHaveBeenCalledWith("Failed to load the test components", failure);

    await loader.load();
    expect(mockedLoadExtensions).toHaveBeenCalledTimes(2);
  });

  describe("useLoaded", () => {
    it("turns true once the load is done", async () => {
      const { result } = renderHook(createExtensionLoader(POINT, "test components").useLoaded);

      expect(result.current).toBe(false);
      await waitFor(() => expect(result.current).toBe(true));
    });

    // Whatever waits on it is drawn without the extensions rather than held back for good
    it("turns true once a failed load is over", async () => {
      vi.spyOn(console, "error").mockImplementation(() => undefined);
      mockedLoadExtensions.mockRejectedValueOnce(new Error("the point could not be read"));

      const { result } = renderHook(createExtensionLoader(POINT, "test components").useLoaded);

      expect(result.current).toBe(false);
      await waitFor(() => expect(result.current).toBe(true));
    });

    it("is true from the first render once the extensions are loaded", async () => {
      const loader = createExtensionLoader(POINT, "test components");
      await loader.load();

      const { result } = renderHook(loader.useLoaded);

      expect(result.current).toBe(true);
    });
  });
});
