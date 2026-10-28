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

import { usePendingWork } from "@iap/frontend-commons/usePendingWork";

describe("usePendingWork", () => {
  it("is done at once when nothing is pending", async () => {
    const { result } = renderHook(() => usePendingWork());

    await expect(result.current.allDone()).resolves.toBeUndefined();
  });

  it("waits for the work tracked so far", async () => {
    const { result } = renderHook(() => usePendingWork());
    const save = Promise.withResolvers<undefined>();
    result.current.track(save.promise);
    const done = vi.fn();

    const waiting = result.current.allDone().then(done);
    await Promise.resolve();
    expect(done).not.toHaveBeenCalled();

    save.resolve(undefined);
    await waiting;
    expect(done).toHaveBeenCalled();
  });

  it("fails with the work that failed", async () => {
    const { result } = renderHook(() => usePendingWork());
    const save = Promise.withResolvers<undefined>();
    result.current.track(save.promise);

    const waiting = result.current.allDone();
    save.reject(new Error("refused"));

    await expect(waiting).rejects.toThrow("refused");
  });

  it("forgets work once it is over", async () => {
    const { result } = renderHook(() => usePendingWork());
    const save = Promise.withResolvers<undefined>();
    result.current.track(save.promise);
    save.reject(new Error("refused"));
    await save.promise.catch(() => undefined);

    await expect(result.current.allDone()).resolves.toBeUndefined();
  });
});
