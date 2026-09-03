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

let urls: string[] = [];

// Answers every request with the given body and status. Each answer carries the `url` it came back
// from: the requests go through useAuthenticatedFetch, which reads it to tell an ordinary response
// from the login page Sling redirects to when the session has expired.
const stubFetch = (body: unknown, status = 200) =>
  vi.stubGlobal("fetch", vi.fn((url: string) => {
    urls.push(url);
    return Promise.resolve({
      ok: status < 400,
      status,
      url,
      json: () => Promise.resolve(body),
    } as unknown as Response);
  }));

beforeEach(() => { urls = []; });
afterEach(() => { vi.unstubAllGlobals(); });

describe("useCatcherStatus", () => {
  it("reads whether mail is being caught, and how much has been", async () => {
    stubFetch({ enabled: true, total: 3 });
    const { result } = renderHook(() => useCatcherStatus());

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(urls).toEqual([ "/CaughtMail.adminSummary.json" ]);
    expect(result.current.status).toEqual({ enabled: true, total: 3 });
  });

  // A reader who reached the console may still not be allowed to read /CaughtMail, and the two
  // callers word that differently — so the hook reports the absence and neither sentence
  it("reports a refusal as no status at all, having settled", async () => {
    stubFetch(null, 403);
    const { result } = renderHook(() => useCatcherStatus());

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(result.current.status).toBeNull();
  });

  it("reports an empty answer the same way", async () => {
    stubFetch(null);
    const { result } = renderHook(() => useCatcherStatus());

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(result.current.status).toBeNull();
  });

  // A dashboard widget is unmounted whenever the reader navigates away, which routinely happens
  // while its first read is still in flight; settling one into a gone component is a React warning
  // and, on a slower page, a leak
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
    stubFetch({ subject: "Approved", to: "someone@uhn.ca" });
    const { result } = renderHook(() => useCaughtMessage("abc"));

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(urls).toEqual([ "/CaughtMail/abc.json" ]);
    expect(result.current.message?.subject).toBe("Approved");
    expect(result.current.message?.to).toEqual([ "someone@uhn.ca" ]);
    expect(result.current.loadError).toBeNull();
  });

  // The wording itself is requestFailure's business; what matters here is that the hook hands the
  // screen a sentence about the cause rather than the protocol, and never one about the attempt —
  // the LoadError above it already says the message could not be read
  it("describes a refusal in the reader's terms, keeping the status to relay", async () => {
    stubFetch(null, 404);
    const { result } = renderHook(() => useCaughtMessage("abc"));

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(result.current.message).toBeNull();
    expect(result.current.loadError).toContain("could not be found on the server");
    expect(result.current.loadError).toContain("(HTTP 404)");
    expect(result.current.loadError).not.toContain("The message could not be read");
  });

  it("describes an answer that was not a node as an unreadable response", async () => {
    stubFetch(null);
    const { result } = renderHook(() => useCaughtMessage("abc"));

    await waitFor(() => { expect(result.current.settled).toBe(true); });
    expect(result.current.loadError).toBe("The server's response could not be read.");
  });

  // A route naming no single message is not a read to make, and not a failure either
  it("reads nothing, and reports nothing, when the route names no message", async () => {
    stubFetch({});
    const { result } = renderHook(() => useCaughtMessage(null));

    await act(async () => { await Promise.resolve(); });
    expect(urls).toEqual([]);
    expect(result.current.message).toBeNull();
    expect(result.current.loadError).toBeNull();
  });

  it("re-reads on retry, through the same path as the first read", async () => {
    stubFetch({ subject: "Approved" });
    const { result } = renderHook(() => useCaughtMessage("abc"));
    await waitFor(() => { expect(result.current.settled).toBe(true); });

    await act(async () => { await result.current.reload(); });
    expect(urls).toEqual([ "/CaughtMail/abc.json", "/CaughtMail/abc.json" ]);
  });

  // Navigating from one message to another must not leave the previous one on screen under the new
  // one's heading
  it("drops the message it was showing when the route names a different one", async () => {
    stubFetch({ subject: "Approved" });
    const { result, rerender } = renderHook(({ name }) => useCaughtMessage(name),
      { initialProps: { name: "abc" as string | null } });
    await waitFor(() => { expect(result.current.message?.subject).toBe("Approved"); });

    stubFetch({ subject: "Rejected" });
    rerender({ name: "def" });
    await waitFor(() => { expect(result.current.message?.subject).toBe("Rejected"); });
    expect(urls).toContain("/CaughtMail/def.json");
  });
});
