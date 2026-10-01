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

import { indexQuestions } from "@iap/schemas/schemaVersionTreeModel";
import { useOptionsFrom } from "@iap/schemas/useOptionsFrom";

const question = (name: string, optionsFrom?: string) => ({
  "@path": `/Schemas/s/v1/form/${name}`, "sling:resourceType": "sch/Question",
  "sling:resourceSuperType": "sch/FormItem", "jcr:uuid": name, ...optionsFrom ? { optionsFrom } : {},
});

const version = {
  "@path": "/Schemas/s/v1",
  "form": {
    "@path": "/Schemas/s/v1/form", "sling:resourceType": "sch/FormRequirement",
    "sling:resourceSuperType": "sch/Requirement",
    "kind": question("kind", "/Categories"), "again": question("again", "/Categories"),
    "broken": question("broken", "/Broken"), "gone": question("gone", "/Gone"), "plain": question("plain"),
  },
};

describe("useOptionsFrom", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("loads the items each path gives once, and none where they cannot be read", async () => {
    const fetch = vi.fn((url: string) => {
      if (url.startsWith("/Gone")) {
        return Promise.reject(new TypeError("offline"));
      }
      const body = url.startsWith("/Categories")
        ? { "@path": "/Categories", "retro": { "@path": "/Categories/retro", "label": "Retrospective" } } : {};
      const response = { ok: url.startsWith("/Categories"), status: 404, url, json: () => Promise.resolve(body) };
      return Promise.resolve(response as unknown as Response);
    });
    vi.stubGlobal("fetch", fetch);

    const { result, unmount } = renderHook(() => useOptionsFrom(indexQuestions(version)));

    await waitFor(() => expect(result.current).toEqual({
      "/Broken": [], "/Categories": [ { value: "/Categories/retro", label: "Retrospective" } ], "/Gone": [],
    }));
    expect(fetch.mock.calls.filter(([ url ]) => url === "/Categories.deep.simple.json")).toHaveLength(1);
    unmount();

    // Gone before they are read, it keeps nothing
    renderHook(() => useOptionsFrom(indexQuestions(version))).unmount();
  });
});
