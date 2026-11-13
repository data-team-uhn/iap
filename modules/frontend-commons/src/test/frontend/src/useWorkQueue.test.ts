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

import { renderHook } from "@testing-library/react";

import { useWorkQueue } from "@iap/frontend-commons/useWorkQueue";

// Lets every promise callback already due run
function settle(): Promise<void> {
  return new Promise(resolve => {
    setTimeout(resolve, 0);
  });
}

describe("useWorkQueue", () => {
  it("starts work at once when nothing is queued", async () => {
    const { result } = renderHook(() => useWorkQueue());
    const start = vi.fn(() => Promise.resolve());

    void result.current(start);
    await settle();

    expect(start).toHaveBeenCalledTimes(1);
  });

  it("starts each piece of work once the one before it has succeeded", async () => {
    const { result } = renderHook(() => useWorkQueue());
    const first = Promise.withResolvers<undefined>();
    const second = vi.fn(() => Promise.resolve());

    void result.current(() => first.promise);
    void result.current(second);
    await settle();
    expect(second).not.toHaveBeenCalled();

    first.resolve(undefined);
    await settle();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it("starts the next piece of work once the one before it has failed", async () => {
    const { result } = renderHook(() => useWorkQueue());
    const first = Promise.withResolvers<undefined>();
    const second = vi.fn(() => Promise.resolve());

    const failing = result.current(() => first.promise);
    void result.current(second);
    await settle();
    expect(second).not.toHaveBeenCalled();

    first.reject(new Error("refused"));
    await expect(failing).rejects.toThrow("refused");
    await settle();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it("gives each caller the result of its own work", async () => {
    const { result } = renderHook(() => useWorkQueue());

    const failing = result.current(() => Promise.reject(new Error("refused")));
    const succeeding = result.current(() => Promise.resolve("saved"));

    await expect(failing).rejects.toThrow("refused");
    await expect(succeeding).resolves.toBe("saved");
  });

  it("keeps one queue across renders", async () => {
    const { result, rerender } = renderHook(() => useWorkQueue());
    const first = Promise.withResolvers<undefined>();
    const second = vi.fn(() => Promise.resolve());
    const before = result.current;

    void before(() => first.promise);
    rerender();
    void result.current(second);
    await settle();

    expect(result.current).toBe(before);
    expect(second).not.toHaveBeenCalled();
    first.resolve(undefined);
    await settle();
    expect(second).toHaveBeenCalledTimes(1);
  });
});
