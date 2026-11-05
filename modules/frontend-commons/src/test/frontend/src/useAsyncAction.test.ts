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

import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

// An action that finishes when the test says, so that the test can see it running.
const pending = () => {
  const settle: { resolve?: () => void } = {};
  const promise = new Promise<void>(resolve => { settle.resolve = resolve; });
  return { action: () => promise, finish: () => settle.resolve?.(), promise };
};

const describing = (error: unknown) => String(error);

// Runs an action with a known outcome and waits for it to settle, inside `act`, so that React applies
// the hook's state updates before the test looks.
const settle = async (run: (action: () => Promise<unknown>) => void, outcome: Promise<unknown>) => {
  const action = () => outcome;
  await act(async () => {
    run(action);
    await outcome.catch(() => undefined);
  });
};

describe("useAsyncAction", () => {
  it("is idle until it is given something to do", () => {
    const { result } = renderHook(() => useAsyncAction<string>({ onFailure: describing }));

    expect(result.current.working).toBe(false);
    expect(result.current.failure).toBeUndefined();
  });

  it("reports as busy for as long as the action takes", async () => {
    const { action, finish, promise } = pending();
    const { result } = renderHook(() => useAsyncAction<string>({ onFailure: describing }));

    act(() => { result.current.run(action); });
    expect(result.current.working).toBe(true);

    finish();
    await act(() => promise);
    expect(result.current.working).toBe(false);
  });

  it("tells the caller once the action has landed", async () => {
    const onSuccess = vi.fn();
    const { result } = renderHook(() => useAsyncAction<string>({ onFailure: describing, onSuccess }));

    await settle(result.current.run, Promise.resolve());

    expect(onSuccess).toHaveBeenCalled();
    expect(result.current.failure).toBeUndefined();
    expect(result.current.working).toBe(false);
  });

  it("keeps whatever the caller makes of a rejection", async () => {
    const onSuccess = vi.fn();
    const { result } = renderHook(() => useAsyncAction({
      onFailure: (error: unknown) => ({ lead: "It did not work", detail: String(error) }),
      onSuccess,
    }));

    await settle(result.current.run, Promise.reject(new Error("HTTP 500")));

    expect(result.current.failure).toEqual({ lead: "It did not work", detail: "Error: HTTP 500" });
    expect(result.current.working).toBe(false);
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it("says nothing about a rejection the caller declines to make anything of", async () => {
    const onFailure = vi.fn().mockReturnValue(undefined);
    const { result } = renderHook(() => useAsyncAction<string>({ onFailure }));

    await settle(result.current.run, Promise.reject(new Error("claimed elsewhere")));

    expect(onFailure).toHaveBeenCalledWith(new Error("claimed elsewhere"));
    expect(result.current.failure).toBeUndefined();
    expect(result.current.working).toBe(false);
  });

  it("drops the last failure while trying again, rather than showing it during the attempt", async () => {
    const { action, finish, promise } = pending();
    const { result } = renderHook(() => useAsyncAction<string>({ onFailure: describing }));
    await settle(result.current.run, Promise.reject(new Error("first go")));
    expect(result.current.failure).toBeDefined();

    act(() => { result.current.run(action); });

    expect(result.current.failure).toBeUndefined();
    finish();
    await act(() => promise);
  });
});
