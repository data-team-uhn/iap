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

import type { useTagChoices as UseTagChoices } from "@iap/conditions/useTagChoices";
import { tagAwareFetch } from "@iap/tags/tagDefinitions.fixture";

let useTagChoices: typeof UseTagChoices;

describe("useTagChoices", () => {
  // Each test starts with no tag definitions cached
  beforeEach(async () => {
    vi.resetModules();
    ({ useTagChoices } = await import("@iap/conditions/useTagChoices"));
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));
  });

  afterEach(() => vi.unstubAllGlobals());

  it("offers the defined tags by their labels", async () => {
    const { result } = renderHook(() => useTagChoices());

    expect(result.current).toEqual([]);
    await waitFor(() => expect(result.current).toContainEqual({ value: "in-review", label: "In review" }));
  });

  it("uses a tag's name when it has no label, and stops listening once gone", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: true, url: "",
      json: () => Promise.resolve({ tags: [ { name: "plain" } ] }) } as unknown as Response)));
    const { result } = renderHook(() => useTagChoices());
    await waitFor(() => expect(result.current).toEqual([ { value: "plain", label: "plain" } ]));

    const { unmount } = renderHook(() => useTagChoices());
    unmount();
  });
});
