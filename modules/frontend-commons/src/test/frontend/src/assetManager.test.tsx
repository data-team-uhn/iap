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

describe("loadAsset", () => {
  it("treats a missing asset dependencies manifest as empty, fetching it only once", async () => {
    // Silence the expected "Unknown asset" complaints for the fake assets used below
    const error = vi.spyOn(console, "error").mockImplementation(() => {});
    const fetchMock = vi.fn((url: RequestInfo | URL) => Promise.resolve((
      String(url).endsWith("assetDependencies.json")
        ? { ok: false, status: 404 }
        : { ok: true, json: () => Promise.resolve({}) }
    ) as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);
    try {
      await loadAsset("asset:iap-test.First.js");
      await loadAsset("asset:iap-test.Second.js");

      // The 404 was remembered as "no dependencies" instead of being re-fetched per asset
      const dependencyFetches = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("assetDependencies.json"));
      expect(dependencyFetches.length).toBe(1);
    } finally {
      vi.unstubAllGlobals();
      error.mockRestore();
    }
  });
});
