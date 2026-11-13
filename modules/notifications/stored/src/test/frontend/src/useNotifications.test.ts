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

describe("useNotifications", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("keeps the last count when neither marking nor counting again succeeds", async () => {
    failsOnceMarking();
    const { result } = renderHook(() => useNotifications());
    await waitFor(() => expect(result.current.unreadCount).toBe(1));

    await act(async () => {
      await expect(result.current.read()).resolves.toBeUndefined();
    });

    expect(result.current.unreadCount).toBe(1);
    expect(result.current.notifications).toHaveLength(1);
    expect(result.current.failed).toBe(false);
  });
});
