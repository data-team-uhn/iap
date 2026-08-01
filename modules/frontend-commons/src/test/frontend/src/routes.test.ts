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

import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// The resolved routes are memoised in module state, so each test starts from a fresh copy of the
// module rather than inheriting whatever the previous one cached.
const freshRoutes = async () => {
  vi.resetModules();
  return (await import("@iap/frontend-commons/routes")).getRoutes;
};

describe("getRoutes", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("resolves the registered Views extensions", async () => {
    const views = [{ "iap:targetURL": "/reports" }];
    mockedLoadExtensions.mockResolvedValue(views);
    const getRoutes = await freshRoutes();

    expect(await getRoutes()).toEqual(views);
    expect(mockedLoadExtensions).toHaveBeenCalledWith("Views");
  });

  it("only asks for the extensions once, and reuses the answer", async () => {
    mockedLoadExtensions.mockResolvedValue([{ "iap:targetURL": "/reports" }]);
    const getRoutes = await freshRoutes();

    const first = await getRoutes();
    const second = await getRoutes();

    expect(second).toBe(first);
    expect(mockedLoadExtensions).toHaveBeenCalledTimes(1);
  });

  it("shares one in-flight request between concurrent callers", async () => {
    type Extensions = Awaited<ReturnType<typeof loadExtensions>>;
    let resolveExtensions: (value: Extensions) => void = () => { /* replaced below */ };
    mockedLoadExtensions.mockReturnValue(new Promise<Extensions>(resolve => { resolveExtensions = resolve; }));
    const getRoutes = await freshRoutes();

    const both = Promise.all([getRoutes(), getRoutes()]);
    resolveExtensions([{ "iap:targetURL": "/reports" }]);

    const [first, second] = await both;
    expect(first).toEqual([{ "iap:targetURL": "/reports" }]);
    expect(second).toEqual(first);
    expect(mockedLoadExtensions).toHaveBeenCalledTimes(1);
  });

  it("reports a failure and resolves to nothing", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    const failure = new Error("no extensions");
    mockedLoadExtensions.mockRejectedValue(failure);
    const getRoutes = await freshRoutes();

    expect(await getRoutes()).toBeUndefined();
    expect(errorSpy).toHaveBeenCalledWith("Failed to resolve routes", failure);
    errorSpy.mockRestore();
  });

  it("retries after a failure, since nothing was cached", async () => {
    vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    mockedLoadExtensions.mockRejectedValueOnce(new Error("no extensions"));
    const getRoutes = await freshRoutes();
    await getRoutes();

    const views = [{ "iap:targetURL": "/reports" }];
    mockedLoadExtensions.mockResolvedValueOnce(views);

    expect(await getRoutes()).toEqual(views);
    expect(mockedLoadExtensions).toHaveBeenCalledTimes(2);
  });
});
