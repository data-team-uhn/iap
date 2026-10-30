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
import { describe, expect, it, vi } from "vitest";

import {
  loadRequirementComponents,
  REQUIREMENT_COMPONENT_POINT,
  useRequirementComponents,
} from "@iap/submissions/requirements";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// The loader keeps its answer for the module's life, so these cases run in order: a failed load
// first, then one that works.
describe("loading the requirement components other modules contribute", () => {
  it("says it is ready even when the load fails, so the form is drawn rather than held back", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    mockedLoadExtensions.mockRejectedValueOnce(new Error("the point could not be read"));

    const { result } = renderHook(() => useRequirementComponents());

    expect(result.current).toBe(false);
    await waitFor(() => expect(result.current).toBe(true));
    expect(error).toHaveBeenCalled();
    error.mockRestore();
  });

  it("asks the repository which ones are declared, rather than naming them", async () => {
    mockedLoadExtensions.mockResolvedValue([]);

    await loadRequirementComponents();

    expect(mockedLoadExtensions).toHaveBeenLastCalledWith(REQUIREMENT_COMPONENT_POINT);
  });

  it("loads them once however many times it is asked", async () => {
    const before = mockedLoadExtensions.mock.calls.length;

    await loadRequirementComponents();
    const { result } = renderHook(() => useRequirementComponents());

    expect(result.current).toBe(true);
    expect(mockedLoadExtensions.mock.calls).toHaveLength(before);
  });

  // An editor closed before the load settles must not be told about it
  it("leaves an editor that has gone alone", () => {
    const { unmount } = renderHook(() => useRequirementComponents());

    expect(() => unmount()).not.toThrow();
  });
});
