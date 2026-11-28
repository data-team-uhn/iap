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

import { act, renderHook, waitFor } from "@testing-library/react";

import { jsonResponse, stubFetch } from "@iap/frontend-commons/fetch.fixture";
import { indexQuestions } from "@iap/schemas/schemaVersionTreeModel";
import { useOptionsFrom } from "@iap/schemas/useOptionsFrom";

const question = (name: string, optionsFrom?: string) => ({
  "@path": `/Schemas/s/v1/form/${name}`, "sling:resourceType": "sch/Question",
  "sling:resourceSuperType": "sch/FormItem", "jcr:uuid": name, ...optionsFrom ? { optionsFrom } : {},
});

const createVersion = (questions: Record<string, object>) => ({
  "@path": "/Schemas/s/v1",
  "form": {
    "@path": "/Schemas/s/v1/form", "sling:resourceType": "sch/FormRequirement",
    "sling:resourceSuperType": "sch/Requirement", ...questions,
  },
});

const version = createVersion({
  "kind": question("kind", "/Categories"), "again": question("again", "/Categories"),
  "broken": question("broken", "/Broken"), "gone": question("gone", "/Gone"), "plain": question("plain"),
});

describe("useOptionsFrom", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("asks the server for each path's options once, and gives none where they cannot be read", async () => {
    const fetch = stubFetch(({ url }) => {
      if (url.includes("/gone.")) {
        return Promise.reject(new TypeError("offline"));
      }
      return url.includes("/kind.") || url.includes("/again.")
        ? jsonResponse({ status: "ok", options: [ { value: "/Categories/retro", label: "Retrospective", description: "" } ] })
        : jsonResponse({}, 404);
    });

    const { result, unmount } = renderHook(() => useOptionsFrom(indexQuestions(version)));

    await waitFor(() => expect(result.current).toEqual({
      "/Broken": [], "/Categories": [ { value: "/Categories/retro", label: "Retrospective" } ], "/Gone": [],
    }));
    expect(fetch.mock.calls.filter(([ url ]) => /\/(kind|again)\.offeredOptions\.json$/.test(url))).toHaveLength(1);
    unmount();
  });

  it("keeps the options of the questions now shown when an older answer arrives late", async () => {
    let answerLate: () => void = () => undefined;
    const body = { status: "ok", options: [ { value: "/Choices/a", label: "A", description: "" } ] };
    const response = { ok: true, status: 200, url: "", json: () => Promise.resolve(body) } as unknown as Response;
    vi.stubGlobal("fetch", vi.fn((url: string) => url.includes("/kind.")
      ? new Promise<Response>(resolve => {
        answerLate = () => resolve(response);
      })
      : Promise.resolve(response)));
    const expected = { "/Other": [ { value: "/Choices/a", label: "A" } ] };

    const { result, rerender } = renderHook(({ shown }) => useOptionsFrom(indexQuestions(shown)),
      { initialProps: { shown: createVersion({ "kind": question("kind", "/Categories") }) } });
    rerender({ shown: createVersion({ "other": question("other", "/Other") }) });
    await waitFor(() => expect(result.current).toEqual(expected));
    answerLate();
    await act(() => new Promise(resolve => setTimeout(resolve, 0)));

    expect(result.current).toEqual(expected);
  });
});
