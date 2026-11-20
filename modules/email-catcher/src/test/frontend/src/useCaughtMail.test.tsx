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

import { useCatcherStatus, useCaughtMessage } from "@iap/email-catcher/useCaughtMail";
import { jsonResponse, sentRequests, stubFetch } from "@iap/frontend-commons/fetch.fixture";

// Answers every request with the given body and status
const answering = (body: unknown, status = 200) => stubFetch(() => jsonResponse(body, status));

const urls = () => sentRequests().map(request => request.url);

afterEach(() => { vi.unstubAllGlobals(); });

describe("useCatcherStatus", () => {
  it("reads whether mail is being caught, and how much has been", async () => {
    answering({ enabled: { label: "Catching mail", value: true }, total: { label: "Caught so far", value: 3 } });
    const { result } = renderHook(() => useCatcherStatus());

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(urls()).toEqual([ "/CaughtMail.adminSummary.json" ]);
    expect(result.current.status).toEqual({ enabled: true, total: 3 });
  });

  it("reports a refusal as no status at all, having settled", async () => {
    answering(null, 403);
    const { result } = renderHook(() => useCatcherStatus());

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(result.current.status).toBeNull();
  });

  it("reports an empty answer the same way", async () => {
    answering(null);
    const { result } = renderHook(() => useCatcherStatus());

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(result.current.status).toBeNull();
  });

  it("says nothing when the read fails after the reader has navigated away", async () => {
    let refuse: (error: Error) => void = () => { /* replaced by the stub below */ };
    vi.stubGlobal("fetch", vi.fn(() =>
      new Promise<Response>((_resolve, reject) => { refuse = reject; })));
    const { result, unmount } = renderHook(() => useCatcherStatus());

    unmount();
    await act(async () => {
      refuse(new TypeError("Failed to fetch"));
      await new Promise(resolve => { setTimeout(resolve, 0); });
    });

    expect(result.current.settled).toBe(false);
    expect(result.current.status).toBeNull();
  });
});

describe("useCaughtMessage", () => {
  it("reads the message the route names", async () => {
    answering({ subject: "Approved", to: "someone@uhn.ca" });
    const { result } = renderHook(() => useCaughtMessage("abc"));

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(urls()).toEqual([ "/CaughtMail/abc.json" ]);
    expect(result.current.message?.subject).toBe("Approved");
    expect(result.current.message?.to).toEqual([ "someone@uhn.ca" ]);
    expect(result.current.loadError).toBeNull();
  });

  // The sentence says the cause, not the attempt, which the page's LoadError title already says
  it("describes a refusal in the reader's terms, keeping the status to relay", async () => {
    answering(null, 404);
    const { result } = renderHook(() => useCaughtMessage("abc"));

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(result.current.message).toBeNull();
    expect(result.current.loadError).toContain("could not be found on the server");
    expect(result.current.loadError).toContain("(HTTP 404)");
    expect(result.current.loadError).not.toContain("The message could not be read");
  });

  it("describes an answer that was not a node as an unreadable response", async () => {
    answering(null);
    const { result } = renderHook(() => useCaughtMessage("abc"));

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(result.current.loadError).toBe("The server's response could not be read. Reload and try again.");
  });

  it("reads nothing, and reports nothing, when the route names no message", async () => {
    answering({});
    const { result } = renderHook(() => useCaughtMessage(null));

    await act(async () => { await Promise.resolve(); });
    expect(urls()).toEqual([]);
    expect(result.current.message).toBeNull();
    expect(result.current.loadError).toBeNull();
  });

  it("re-reads on retry, through the same path as the first read", async () => {
    answering({ subject: "Approved" });
    const { result } = renderHook(() => useCaughtMessage("abc"));
    await waitFor(() => { expect(result.current.settled).toBe(true); });

    await act(async () => { await result.current.reload(); });
    expect(urls()).toEqual([ "/CaughtMail/abc.json", "/CaughtMail/abc.json" ]);
  });

  it("drops the message it was showing when the route names a different one", async () => {
    answering({ subject: "Approved" });
    const { result, rerender } = renderHook(({ name }) => useCaughtMessage(name),
      { initialProps: { name: "abc" } });
    await waitFor(() => { expect(result.current.message?.subject).toBe("Approved"); });

    answering({ subject: "Rejected" });
    rerender({ name: "def" });
    await waitFor(() => { expect(result.current.message?.subject).toBe("Rejected"); });
    expect(urls()).toContain("/CaughtMail/def.json");
  });
});
