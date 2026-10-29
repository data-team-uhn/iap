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

import { act, renderHook } from "@testing-library/react";

import { useStoredSetting } from "@iap/frontend-commons/useStoredSetting";

const KEY = "iap.test.colour";

const isColour = (value: unknown): value is "red" | "blue" => value === "red" || value === "blue";

function renderSetting() {
  return renderHook(() => useStoredSetting<"red" | "blue">(KEY, "red", isColour));
}

describe("useStoredSetting", () => {
  afterEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it("is the fallback while nothing is stored", () => {
    expect(renderSetting().result.current[0]).toBe("red");
  });

  it("is what was stored, on the next visit too", () => {
    const { result } = renderSetting();

    act(() => result.current[1]("blue"));

    expect(result.current[0]).toBe("blue");
    expect(window.localStorage.getItem(KEY)).toBe("\"blue\"");
    expect(renderSetting().result.current[0]).toBe("blue");
  });

  it("is the fallback for a stored value it does not accept", () => {
    window.localStorage.setItem(KEY, "\"green\"");
    expect(renderSetting().result.current[0]).toBe("red");

    window.localStorage.setItem(KEY, "not JSON");
    expect(renderSetting().result.current[0]).toBe("red");
  });

  it("reads the setting kept under another key when given one", () => {
    window.localStorage.setItem(KEY, "\"blue\"");
    const { result, rerender } = renderHook(
      ({ key }) => useStoredSetting<"red" | "blue">(key, "red", isColour),
      { initialProps: { key: KEY } },
    );
    expect(result.current[0]).toBe("blue");

    // Nothing is kept under the other key yet
    rerender({ key: "iap.test.other" });
    expect(result.current[0]).toBe("red");

    act(() => result.current[1]("red"));
    expect(window.localStorage.getItem("iap.test.other")).toBe("\"red\"");
    expect(window.localStorage.getItem(KEY)).toBe("\"blue\"");

    rerender({ key: KEY });
    expect(result.current[0]).toBe("blue");
  });

  it("works without storage, keeping a change for the visit", () => {
    vi.spyOn(window.localStorage, "getItem").mockImplementation(() => {
      throw new DOMException("Blocked", "SecurityError");
    });
    vi.spyOn(window.localStorage, "setItem").mockImplementation(() => {
      throw new DOMException("Blocked", "SecurityError");
    });
    const { result } = renderSetting();

    expect(result.current[0]).toBe("red");
    act(() => result.current[1]("blue"));
    expect(result.current[0]).toBe("blue");
  });
});
