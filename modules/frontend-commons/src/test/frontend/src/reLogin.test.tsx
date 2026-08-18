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

import type { ReactNode } from "react";

import { render, screen, waitFor } from "@testing-library/react";

import {
  isNotAuthenticated,
  NotAuthenticatedError,
  ReLoginContext,
  useAuthenticatedFetch,
  type RequestReLogin
} from "@iap/frontend-commons/reLogin";

const SESSION_INFO_URL = "/system/sling/info.sessionInfo.json";

const ok = (body = "payload") => ({ ok: true, status: 200, url: "/data.json", text: () => Promise.resolve(body) });
const unauthenticated = () => ({ ok: false, status: 401, url: "/data.json" });
const loginPage = () => ({ ok: true, status: 200, url: `${window.location.origin}/login?resource=/data.json` });
const serverError = (body = "boom") => ({ ok: false, status: 500, url: "/data.json", text: () => Promise.resolve(body) });

// What /system/sling/info.sessionInfo.json says when the session is, or is not, still there
const sessionInfo = (userID: string | undefined) => ({
  ok: true,
  status: 200,
  url: SESSION_INFO_URL,
  json: () => Promise.resolve({ userID }),
});

// Reports whatever the hook's fetch settled with, so the tests can assert on it from the DOM.
function Caller({ onResult }: { onResult?: (outcome: string) => void }) {
  const authenticatedFetch = useAuthenticatedFetch();
  return (
    <button
      type="button"
      onClick={() => {
        authenticatedFetch("/data.json")
          .then(response => response.text())
          .then(text => onResult?.(`resolved:${text}`))
          .catch((err: unknown) => onResult?.(`rejected:${(err as Error).message}`));
      }}
    >
      Fetch
    </button>
  );
}

const renderCaller = (requestReLogin: RequestReLogin | null, onResult?: (outcome: string) => void) => {
  const tree = (children: ReactNode) => requestReLogin
    ? <ReLoginContext value={requestReLogin}>{children}</ReLoginContext>
    : children;
  return render(<>{tree(<Caller onResult={onResult} />)}</>);
};

// Typed, unlike the bare `vi.fn()` elsewhere: these tests drive the mock through
// mockImplementation, and an untyped mock is declared as returning void, which makes handing it an
// async implementation an error.
type FetchMock = ReturnType<typeof vi.fn<(url: string) => Promise<unknown>>>;

describe("useAuthenticatedFetch", () => {
  let fetchMock: FetchMock;

  beforeEach(() => {
    fetchMock = vi.fn<(url: string) => Promise<unknown>>();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // Answers the request under test with `responses` in order, and the session probe with whether the
  // session is still there. The probe can interleave with the retries, so it is matched by URL
  // rather than by call order.
  const answer = (responses: object[], authenticated = true) => {
    const queue = [...responses];
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(url === SESSION_INFO_URL
        ? sessionInfo(authenticated ? "jdoe" : "anonymous")
        : queue.shift() ?? ok("exhausted")));
  };

  const requestsFor = (url: string) => fetchMock.mock.calls.filter(([called]) => called === url);

  it("passes an ordinary response straight through", async () => {
    const onResult = vi.fn();
    answer([ok()]);
    renderCaller(null, onResult);

    screen.getByRole("button").click();

    await waitFor(() => { expect(onResult).toHaveBeenCalledWith("resolved:payload"); });
    // Nothing ambiguous happened, so the session was never probed
    expect(requestsFor(SESSION_INFO_URL)).toHaveLength(0);
  });

  it("rejects a network failure", async () => {
    const onResult = vi.fn();
    fetchMock.mockRejectedValueOnce(new Error("offline"));
    renderCaller(null, onResult);

    screen.getByRole("button").click();

    await waitFor(() => { expect(onResult).toHaveBeenCalledWith("rejected:offline"); });
  });

  it("rejects a failure that was not an Error", async () => {
    const onResult = vi.fn();
    fetchMock.mockRejectedValueOnce("connection reset");
    renderCaller(null, onResult);

    screen.getByRole("button").click();

    await waitFor(() => { expect(onResult).toHaveBeenCalledWith("rejected:connection reset"); });
  });

  describe("when the session has expired", () => {
    it.each([
      ["a 401", unauthenticated],
      ["a redirect to the login page", loginPage],
    ])("signs in and re-sends the request, given %s", async (_case, expired) => {
      const onResult = vi.fn();
      let recover: ((recovered: boolean) => void) | undefined;
      const requestReLogin = vi.fn(() => new Promise<boolean>(resolve => { recover = resolve; }));
      answer([expired(), ok("after signing in")]);
      renderCaller(requestReLogin, onResult);

      screen.getByRole("button").click();

      // Nothing is reported to the caller yet: the request is waiting on the sign-in
      await waitFor(() => { expect(requestReLogin).toHaveBeenCalled(); });
      expect(onResult).not.toHaveBeenCalled();
      // Unambiguous, so no probe was needed
      expect(requestsFor(SESSION_INFO_URL)).toHaveLength(0);

      recover?.(true);

      await waitFor(() => { expect(onResult).toHaveBeenCalledWith("resolved:after signing in"); });
    });

    it("fails the request when the user abandons signing in", async () => {
      const onResult = vi.fn();
      const requestReLogin = vi.fn(() => Promise.resolve(false));
      answer([unauthenticated()]);
      renderCaller(requestReLogin, onResult);

      screen.getByRole("button").click();

      await waitFor(() => {
        expect(onResult).toHaveBeenCalledWith("rejected:Not authenticated, and signing in was abandoned: /data.json");
      });
    });

    it("asks again when the session is still gone after the retry", async () => {
      const onResult = vi.fn();
      const requestReLogin = vi.fn(() => Promise.resolve(true));
      answer([unauthenticated(), unauthenticated(), ok("third time")]);
      renderCaller(requestReLogin, onResult);

      screen.getByRole("button").click();

      await waitFor(() => { expect(onResult).toHaveBeenCalledWith("resolved:third time"); });
      expect(requestReLogin).toHaveBeenCalledTimes(2);
    });

    it("reports it when there is no sign-in on offer, rather than waiting forever", async () => {
      const onResult = vi.fn();
      answer([unauthenticated()]);
      renderCaller(null, onResult);

      screen.getByRole("button").click();

      await waitFor(() => {
        expect(onResult).toHaveBeenCalledWith("rejected:Not authenticated, and no sign-in is available: /data.json");
      });
    });
  });

  // Sling reports a write attempted with an expired session as a 500 rather than a 401, so a 500 has
  // to be told apart from a genuine server error before deciding what to do with it.
  describe("when a 500 comes back", () => {
    it("recovers the session, when that is what the 500 really meant", async () => {
      const onResult = vi.fn();
      const requestReLogin = vi.fn(() => Promise.resolve(true));
      const queue = [serverError(), ok("saved after signing in")];
      let authenticated = false;
      fetchMock.mockImplementation((url: string) =>
        Promise.resolve(url === SESSION_INFO_URL
          ? sessionInfo(authenticated ? "jdoe" : "anonymous")
          : queue.shift()));
      requestReLogin.mockImplementation(() => { authenticated = true; return Promise.resolve(true); });
      renderCaller(requestReLogin, onResult);

      screen.getByRole("button").click();

      await waitFor(() => { expect(onResult).toHaveBeenCalledWith("resolved:saved after signing in"); });
      expect(requestsFor(SESSION_INFO_URL)).toHaveLength(1);
    });

    it("passes it to the caller when the session is alive, so a real error is not mistaken for one", async () => {
      const onResult = vi.fn();
      const requestReLogin = vi.fn(() => Promise.resolve(true));
      answer([serverError("genuinely broken")], true);
      renderCaller(requestReLogin, onResult);

      screen.getByRole("button").click();

      await waitFor(() => { expect(onResult).toHaveBeenCalledWith("resolved:genuinely broken"); });
      // No sign-in was asked for, and the request was not re-sent
      expect(requestReLogin).not.toHaveBeenCalled();
      expect(requestsFor("/data.json")).toHaveLength(1);
    });

    it("stops asking once the session is back but the 500 persists", async () => {
      const onResult = vi.fn();
      let authenticated = false;
      const requestReLogin = vi.fn(() => { authenticated = true; return Promise.resolve(true); });
      fetchMock.mockImplementation((url: string) =>
        Promise.resolve(url === SESSION_INFO_URL
          ? sessionInfo(authenticated ? "jdoe" : "anonymous")
          : serverError("still broken")));
      renderCaller(requestReLogin, onResult);

      screen.getByRole("button").click();

      // One sign-in, then the second 500 is recognised as a real error rather than prompting again
      await waitFor(() => { expect(onResult).toHaveBeenCalledWith("resolved:still broken"); });
      expect(requestReLogin).toHaveBeenCalledTimes(1);
    });

    it("treats an unreadable session probe as no evidence of an expired session", async () => {
      const onResult = vi.fn();
      const requestReLogin = vi.fn(() => Promise.resolve(true));
      fetchMock.mockImplementation((url: string) =>
        url === SESSION_INFO_URL ? Promise.reject(new Error("probe failed")) : Promise.resolve(serverError()));
      renderCaller(requestReLogin, onResult);

      screen.getByRole("button").click();

      // A failing probe says nothing about the session, so the 500 is reported rather than turned
      // into a sign-in prompt
      await waitFor(() => { expect(onResult).toHaveBeenCalledWith("resolved:boom"); });
      expect(requestReLogin).not.toHaveBeenCalled();
    });

    it.each([
      ["the probe itself is refused", { ok: false, status: 403, url: SESSION_INFO_URL }],
      ["the probe is redirected to the login page", { ok: true, status: 200, url: `${window.location.origin}/login`, json: () => Promise.resolve({}) }],
    ])("recovers the session when %s", async (_case, probe) => {
      const onResult = vi.fn();
      const requestReLogin = vi.fn(() => Promise.resolve(true));
      const queue = [serverError(), ok("recovered")];
      fetchMock.mockImplementation((url: string) =>
        Promise.resolve(url === SESSION_INFO_URL ? probe : queue.shift()));
      renderCaller(requestReLogin, onResult);

      screen.getByRole("button").click();

      await waitFor(() => { expect(requestReLogin).toHaveBeenCalled(); });
      await waitFor(() => { expect(onResult).toHaveBeenCalledWith("resolved:recovered"); });
    });
  });
});

// Why the failure has a type rather than a recognisable message: a caller has to tell "the session is
// gone" apart from "the server could not be reached", and matching on message text would couple them.
describe("isNotAuthenticated", () => {
  it("recognises a session that could not be recovered", () => {
    expect(isNotAuthenticated(new NotAuthenticatedError("gone"))).toBe(true);
  });

  it("does not mistake an ordinary failure for an authentication one", () => {
    expect(isNotAuthenticated(new Error("offline"))).toBe(false);
    expect(isNotAuthenticated("offline")).toBe(false);
  });
});
