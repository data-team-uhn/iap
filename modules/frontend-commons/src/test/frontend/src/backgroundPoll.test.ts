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

import { BACKGROUND_HEADER, backgroundFetch, SessionEndedError, useBackgroundPoll } from "@iap/frontend-commons/backgroundPoll";

// The page's visibility, as the poll reads it. The shared test window outlives this file, so the
// property is removed again afterwards.
let hidden = false;

const setHidden = (value: boolean) => {
  hidden = value;
  document.dispatchEvent(new Event("visibilitychange"));
};

beforeAll(() => {
  Object.defineProperty(document, "hidden", { configurable: true, get: () => hidden });
});

afterAll(() => {
  Reflect.deleteProperty(document, "hidden");
});

beforeEach(() => {
  hidden = false;
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

// A task whose runs finish only when the test says so
function heldTask() {
  const settles: { resolve: () => void; reject: (error: unknown) => void }[] = [];
  const task = vi.fn(() => new Promise<void>((resolve, reject) => { settles.push({ resolve, reject }); }));
  return { task, settles };
}

describe("useBackgroundPoll", () => {
  it("runs now, then again an interval after each run ends", async () => {
    const task = vi.fn(() => Promise.resolve());
    renderHook(() => useBackgroundPoll(task, 1000, false));
    expect(task).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(999);
    expect(task).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(task).toHaveBeenCalledTimes(2);
  });

  it("waits for a slow run before counting the interval", async () => {
    const { task, settles } = heldTask();
    renderHook(() => useBackgroundPoll(task, 1000, false));

    await vi.advanceTimersByTimeAsync(5000);
    expect(task).toHaveBeenCalledTimes(1);
    settles[0].resolve();
    await vi.advanceTimersByTimeAsync(1000);
    expect(task).toHaveBeenCalledTimes(2);
  });

  it("pauses while the page is hidden, and runs as soon as it is shown", async () => {
    const task = vi.fn(() => Promise.resolve());
    renderHook(() => useBackgroundPoll(task, 1000, false));

    setHidden(true);
    await vi.advanceTimersByTimeAsync(5000);
    expect(task).toHaveBeenCalledTimes(1);
    setHidden(false);
    expect(task).toHaveBeenCalledTimes(2);
  });

  it("does not run on a page opened hidden until it is shown", () => {
    hidden = true;
    const task = vi.fn(() => Promise.resolve());
    renderHook(() => useBackgroundPoll(task, 1000, false));
    expect(task).not.toHaveBeenCalled();

    setHidden(false);
    expect(task).toHaveBeenCalledTimes(1);
  });

  it("keeps running while hidden when asked to, starting no second poll when shown", async () => {
    const task = vi.fn(() => Promise.resolve());
    renderHook(() => useBackgroundPoll(task, 1000, true));

    setHidden(true);
    await vi.advanceTimersByTimeAsync(1000);
    expect(task).toHaveBeenCalledTimes(2);
    setHidden(false);
    expect(task).toHaveBeenCalledTimes(2);
  });

  it("stops once the session has ended, until the page is next shown", async () => {
    const task = vi.fn(() => Promise.reject(new SessionEndedError()));
    renderHook(() => useBackgroundPoll(task, 1000, true));

    await vi.advanceTimersByTimeAsync(5000);
    expect(task).toHaveBeenCalledTimes(1);
    // Hidden, though it may run while hidden: the session is still taken to have ended
    setHidden(true);
    expect(task).toHaveBeenCalledTimes(1);
    setHidden(false);
    expect(task).toHaveBeenCalledTimes(2);
  });

  it("goes on after any other failure", async () => {
    const task = vi.fn(() => Promise.reject(new Error("offline")));
    renderHook(() => useBackgroundPoll(task, 1000, false));

    await vi.advanceTimersByTimeAsync(1000);
    expect(task).toHaveBeenCalledTimes(2);
  });

  it("lets a run still in flight when the poll stopped schedule nothing, nor stop the next poll", async () => {
    const { task, settles } = heldTask();
    renderHook(() => useBackgroundPoll(task, 1000, false));
    setHidden(true);
    setHidden(false);
    expect(task).toHaveBeenCalledTimes(2);

    // The first run ends after the poll it belonged to stopped
    settles[0].reject(new SessionEndedError());
    settles[1].resolve();
    await vi.advanceTimersByTimeAsync(1000);
    expect(task).toHaveBeenCalledTimes(3);
  });

  it("stops when the page leaves", async () => {
    const task = vi.fn(() => Promise.resolve());
    const { unmount } = renderHook(() => useBackgroundPoll(task, 1000, false));
    unmount();

    await vi.advanceTimersByTimeAsync(5000);
    setHidden(true);
    setHidden(false);
    expect(task).toHaveBeenCalledTimes(1);
  });
});

describe("backgroundFetch", () => {
  it("marks the request as made in the background, keeping its other headers", async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response("{}")));
    vi.stubGlobal("fetch", fetchMock);

    await backgroundFetch("/Notifications.paginate.json", { headers: { Accept: "application/json" } });

    const init = fetchMock.mock.calls[0] as unknown as [ string, RequestInit ];
    const headers = new Headers(init[1].headers);
    expect(headers.get(BACKGROUND_HEADER)).toBe("1");
    expect(headers.get("Accept")).toBe("application/json");
  });

  it("rejects once the session has ended, instead of asking to sign in", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(null, { status: 401 }))));
    await expect(backgroundFetch("/x")).rejects.toBeInstanceOf(SessionEndedError);

    const loginPage = new Response("<html></html>");
    Object.defineProperty(loginPage, "url", { value: `${window.location.origin}/login?resource=%2Fx` });
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(loginPage)));
    await expect(backgroundFetch("/x")).rejects.toBeInstanceOf(SessionEndedError);
  });

  it("hands any other answer to the caller", async () => {
    const failure = new Response(null, { status: 500 });
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(failure)));

    await expect(backgroundFetch("/x")).resolves.toBe(failure);
  });
});
