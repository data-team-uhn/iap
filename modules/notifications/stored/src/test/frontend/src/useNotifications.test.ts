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

import { BACKGROUND_HEADER } from "@iap/frontend-commons/backgroundPoll";
import { FakeNotification, installNotifications } from "@iap/frontend-commons/systemNotifications.fixture";
import { useNotifications } from "@iap/stored-notifications/useNotifications";

const doFetch = vi.fn();

vi.mock("@iap/frontend-commons/reLogin", async importOriginal => ({
  ...await importOriginal<Record<string, unknown>>(),
  useAuthenticatedFetch: () => doFetch,
}));

const UNREAD = {
  "@path": "/Notifications/me/aa/bb/cc/one",
  "@name": "one",
  message: "Something happened to one",
  read: false,
  subject: "/Submissions/one",
};

function page(rows: unknown[], totalrows: number) {
  return { ok: true, status: 200, url: "", json: () => Promise.resolve(
    { rows, offset: 0, limit: 100, returnedrows: rows.length, totalrows, totalIsApproximate: false }) };
}

// Answers the list with the given rows, and the unread count with how many of them are unread
function listing(rows: typeof UNREAD[]) {
  return (url: string) => Promise.resolve(url.includes("fieldName=read")
    ? page([], rows.filter(row => !row.read).length)
    : page(rows, rows.length));
}

// The poll's own fetch, answering as the server would with the given rows for now
const server = vi.fn();

// Lists one unread notification and counts it, until the attempt to mark it read fails: from then
// on, the count cannot be loaded either
function failsOnceMarking() {
  let marked = false;
  doFetch.mockImplementation((url: string) => {
    if (url.endsWith(".markRead.json")) {
      marked = true;
      return Promise.resolve({ ok: false, status: 500, url: "", json: () => Promise.resolve({}) });
    }
    if (url.includes("fieldName=read")) {
      return marked ? Promise.reject(new Error("offline")) : Promise.resolve(page([], 1));
    }
    return Promise.resolve(page([ UNREAD ], 1));
  });
}

// Lets the poll's requests and what follows them finish
const settle = () => act(() => vi.advanceTimersByTimeAsync(0));

const nextMinute = () => act(() => vi.advanceTimersByTimeAsync(60_000));

describe("useNotifications", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers();
    server.mockImplementation(listing([ UNREAD ]));
    vi.stubGlobal("fetch", server);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("polls in the background, so that a page left open does not keep the session alive", async () => {
    const { result } = renderHook(() => useNotifications());
    await settle();

    expect(result.current.unreadCount).toBe(1);
    expect(result.current.notifications).toHaveLength(1);
    expect(server.mock.calls.every(([ , init ]) =>
      new Headers((init as RequestInit).headers).get(BACKGROUND_HEADER) === "1")).toBe(true);
    expect(doFetch).not.toHaveBeenCalled();
  });

  it("keeps the last count when neither marking nor counting again succeeds", async () => {
    failsOnceMarking();
    const { result } = renderHook(() => useNotifications());
    await settle();

    await act(async () => {
      await expect(result.current.read()).resolves.toBeUndefined();
    });

    expect(result.current.unreadCount).toBe(1);
    expect(result.current.notifications).toHaveLength(1);
    expect(result.current.failed).toBe(false);
  });

  it("announces each new unread notification once the user allowed it", async () => {
    installNotifications("granted");
    vi.stubGlobal("fetch", server);
    server.mockImplementation(listing([]));
    renderHook(() => useNotifications());
    await settle();

    server.mockImplementation(listing([ UNREAD, { ...UNREAD, "@path": "/Notifications/me/old", read: true } ]));
    await nextMinute();

    expect(FakeNotification.shown).toHaveLength(1);
    expect(FakeNotification.shown[0].options).toEqual(
      { body: "Something happened to one", tag: "/Notifications/me/aa/bb/cc/one" });
  });

  it("announces neither what was there when the page opened, nor what the user has seen", async () => {
    installNotifications("granted");
    vi.stubGlobal("fetch", server);
    const { result } = renderHook(() => useNotifications());
    await settle();
    await nextMinute();
    expect(FakeNotification.shown).toHaveLength(0);

    const later = { ...UNREAD, "@path": "/Notifications/me/later" };
    doFetch.mockImplementation(listing([ later, UNREAD ]));
    await act(() => result.current.read());
    server.mockImplementation(listing([ later, UNREAD ]));
    await nextMinute();

    expect(FakeNotification.shown).toHaveLength(0);
  });

  it("asks to show notifications when the list is opened, and announces from then on", async () => {
    installNotifications("default");
    vi.stubGlobal("fetch", server);
    server.mockImplementation(listing([]));
    doFetch.mockImplementation(listing([]));
    const { result } = renderHook(() => useNotifications());
    await settle();

    await act(() => result.current.read());
    expect(FakeNotification.requestPermission).toHaveBeenCalled();

    server.mockImplementation(listing([ UNREAD ]));
    await nextMinute();
    expect(FakeNotification.shown).toHaveLength(1);
  });

  it("says the list could not be loaded when opening it fails", async () => {
    doFetch.mockRejectedValue(new Error("offline"));
    const { result } = renderHook(() => useNotifications());
    await settle();

    await act(() => result.current.read());

    expect(result.current.failed).toBe(true);
  });
});
