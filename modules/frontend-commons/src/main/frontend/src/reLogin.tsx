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

import { createContext, useCallback, useContext } from "react";

// Asks the application to sign the user in again. It resolves to `true` once there is a session to
// retry against, and to `false` if the user abandoned the sign-in. The requests waiting on it are
// sent again on `true`, and fail on `false`. A sign-in that never settles leaves them waiting.
//
// It says nothing about how the user signs in.
export type RequestReLogin = () => Promise<boolean>;

// The sign-in a re-login provider offers; null if no provider is mounted, and then an expired session
// is reported as an error.
export const ReLoginContext = createContext<RequestReLogin | null>(null);

// What useAuthenticatedFetch returns. Only a component or a hook can call the hook, so other code
// takes this as an argument.
export type AuthenticatedFetch = (url: string, init?: RequestInit) => Promise<Response>;

// Sling's session endpoint, which says who the current session is authenticated as
export const SESSION_INFO_URL = "/system/sling/info.sessionInfo.json";

// The session is gone and was not recovered. Either the login page came back in place of data, or
// useAuthenticatedFetch could not sign in again: no sign-in is available, or the user abandoned it.
// It is a type of its own, so that a caller can tell it from a network failure and ask the user to
// sign in.
export class NotAuthenticatedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotAuthenticatedError";
  }
}

// Whether a rejection is a NotAuthenticatedError.
export function isNotAuthenticated(error: unknown): boolean {
  return error instanceof NotAuthenticatedError;
}

// Whether the response is the login page. Sling answers an unauthenticated request with a 401, or
// with a redirect to the login page, which arrives as a 200. Only the URL it came from tells it apart.
export function isLoginRedirect(response: Response): boolean {
  return response.ok && response.url.startsWith(`${window.location.origin}/login`);
}

// Whether there is still a session behind this page. Only asked after a 500.
async function stillAuthenticated(): Promise<boolean> {
  try {
    // Never from the cache, where an old answer would report an expired session as live
    const response = await fetch(SESSION_INFO_URL, { cache: "no-store" });
    if (!response.ok || isLoginRedirect(response)) {
      return false;
    }
    const info = await response.json() as { userID?: string };
    return Boolean(info.userID) && info.userID !== "anonymous";
  } catch {
    // The question itself failed, which says nothing about the session. It counts as live, so that a
    // network problem does not become a sign-in prompt.
    return true;
  }
}

// A `fetch` that survives the session expiring. On a 401, the login page, or a 500 from an expired
// session, it asks the user to sign in again and sends the request again. It resolves with the answer
// to the last attempt, so callers need no session handling of their own. It rejects with a
// NotAuthenticatedError if no sign-in is available or the user abandons it.
//
// A write made with an expired session gets a 500 from Sling, not a 401. A 500 can also be a real
// server error, which a sign-in does not fix, and each retry would ask for another sign-in. So after
// a 500 it asks Sling whether the session is still there, and asks for a sign-in only if it is not.
export function useAuthenticatedFetch(): AuthenticatedFetch {
  const requestReLogin = useContext(ReLoginContext);

  return useCallback(async (url: string, init?: RequestInit) => {
    try {
      // One attempt per pass. A recovered session sends the request again, and anything else leaves.
      for (;;) {
        const response = await fetch(url, init);
        const expired = response.status === 401 || isLoginRedirect(response);
        // A 500 from a live session is the server's own failure
        if (!expired && !(response.status === 500 && !await stillAuthenticated())) {
          return response;
        }
        if (!requestReLogin) {
          throw new NotAuthenticatedError(`Not authenticated, and no sign-in is available: ${url}`);
        }
        if (!await requestReLogin()) {
          throw new NotAuthenticatedError(`Not authenticated, and signing in was abandoned: ${url}`);
        }
      }
    } catch (err: unknown) {
      // Callers get an Error, whatever `fetch` or the sign-in rejected with
      throw err instanceof Error ? err : new Error(String(err));
    }
  }, [requestReLogin]);
}
