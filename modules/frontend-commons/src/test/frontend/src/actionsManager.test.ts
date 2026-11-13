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

import { clearActions, getActions } from "@iap/frontend-commons/actionsManager";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({ loadExtensions: vi.fn() }));

const mockedLoadExtensions = vi.mocked(loadExtensions);

const First = () => null;
const Second = () => null;

beforeEach(() => {
  clearActions();
  mockedLoadExtensions.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("getActions", () => {
  it("hands back the components the extension point contributed, in its order", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { "ext:render": First },
      { "ext:render": Second },
    ]);

    await expect(getActions("ThingActions")).resolves.toEqual([First, Second]);
    expect(mockedLoadExtensions).toHaveBeenCalledWith("ThingActions");
  });

  it("leaves out an extension that contributed no component at all", async () => {
    mockedLoadExtensions.mockResolvedValue([ { "ext:name": "Nothing to render" },
      { "ext:render": First } ]);

    await expect(getActions("ThingActions")).resolves.toEqual([First]);
  });

  it("fetches an extension point once, however many components ask for it", async () => {
    mockedLoadExtensions.mockResolvedValue([ { "ext:render": First } ]);

    // Twice at once, then again once loaded
    const [ first, second ] = await Promise.all([ getActions("ThingActions"), getActions("ThingActions") ]);
    const third = await getActions("ThingActions");

    expect(first).toEqual([First]);
    expect(second).toEqual([First]);
    expect(third).toEqual([First]);
    expect(mockedLoadExtensions).toHaveBeenCalledTimes(1);
  });

  it("keeps each extension point's actions apart", async () => {
    mockedLoadExtensions
      .mockResolvedValueOnce([ { "ext:render": First } ])
      .mockResolvedValueOnce([ { "ext:render": Second } ]);

    await expect(getActions("OneKind")).resolves.toEqual([First]);
    await expect(getActions("AnotherKind")).resolves.toEqual([Second]);
  });

  it("shows an extension naming its places only in those, and one naming none everywhere", async () => {
    const Third = () => null;
    mockedLoadExtensions.mockResolvedValue([
      { "ext:render": First, "ext:places": [ "list" ] },
      { "ext:render": Second },
      // A single value, as a property that is not multi-valued arrives
      { "ext:render": Third, "ext:places": "page" },
    ]);

    await expect(getActions("ThingActions", "list")).resolves.toEqual([First, Second]);
    await expect(getActions("ThingActions", "page")).resolves.toEqual([Second, Third]);
    await expect(getActions("ThingActions")).resolves.toEqual([Second]);
    expect(mockedLoadExtensions).toHaveBeenCalledTimes(1);
  });

  it("keeps an extension point that gave no actions, and does not ask again", async () => {
    mockedLoadExtensions.mockResolvedValue([]);

    await expect(getActions("ThingActions")).resolves.toEqual([]);
    await expect(getActions("ThingActions")).resolves.toEqual([]);
    expect(mockedLoadExtensions).toHaveBeenCalledTimes(1);
  });

  it("rejects when the extension point cannot be read, and reads it again next time", async () => {
    // The real loader, reading a point the server refuses
    const actual = await vi.importActual<typeof import("@iap/ui-extension/extensionManager")>(
      "@iap/ui-extension/extensionManager");
    mockedLoadExtensions.mockImplementation(actual.loadExtensions);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 500 })));

    await expect(getActions("ThingActions")).rejects.toThrow("Failed to load extensions from ThingActions: 500");

    mockedLoadExtensions.mockResolvedValue([ { "ext:render": First } ]);
    await expect(getActions("ThingActions")).resolves.toEqual([First]);
    expect(mockedLoadExtensions).toHaveBeenCalledTimes(2);
  });
});
