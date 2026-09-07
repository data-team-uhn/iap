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
    // The loader drops an extension whose asset failed; one that simply has no render property is
    // not an action, and is not rendered as an empty one
    mockedLoadExtensions.mockResolvedValue([ { "ext:name": "Nothing to render" },
      { "ext:render": First } ]);

    await expect(getActions("ThingActions")).resolves.toEqual([First]);
  });

  it("fetches an extension point once, however many components ask for it", async () => {
    mockedLoadExtensions.mockResolvedValue([ { "ext:render": First } ]);

    // Concurrently, then again once resolved: neither is a second fetch
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

  it("resolves to no actions when the extension point cannot be read", async () => {
    // A page whose action bar cannot be built still displays the thing itself, which is what its
    // reader came for
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    mockedLoadExtensions.mockRejectedValue(new Error("Network is down"));

    await expect(getActions("ThingActions")).resolves.toEqual([]);
    expect(console.error).toHaveBeenCalled();

    // The failure is not cached, so a later render can find the actions that were unreachable
    mockedLoadExtensions.mockResolvedValue([ { "ext:render": First } ]);
    await expect(getActions("ThingActions")).resolves.toEqual([First]);

    vi.mocked(console.error).mockRestore();
  });
});
