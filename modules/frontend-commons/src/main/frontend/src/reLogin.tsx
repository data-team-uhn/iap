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

// Asks the surrounding application to get the user authenticated again, resolving to whether that
// worked: `true` once there is a session to retry against, `false` if the user abandoned the
// attempt. Requests waiting on it are re-sent on `true` and fail on `false`, so however sign-in
// happens it must settle this — a provider that resolves neither leaves those requests hanging,
// which is the bug this whole mechanism exists to avoid.
//
// Deliberately says nothing about *how* the user signs in. The credentials dialog in the login
// module is one implementation; a deployment authenticating against an external identity provider
// is expected to supply another, opening a popup window so that this page (and the unsaved work on
// it) survives the round trip.
export type RequestReLogin = () => Promise<boolean>;

// Provided by a re-login provider, e.g. <ReLoginProvider> in the login module. Absent when none is
// mounted, in which case an expired session is reported as an error rather than recovered from.
export const ReLoginContext = createContext<RequestReLogin | null>(null);

const SESSION_INFO_URL = "/system/sling/info.sessionInfo.json";

// Sling answers an unauthenticated request either with a 401, or -- when the authentication handler
// prefers to redirect -- with a 200 whose body is the login page, recognisable only from the URL the
// response came back from.
function isLoginRedirect(response: Response): boolean {
  return response.ok && response.url.startsWith(`${window.location.origin}/login`);
}

// Whether there is still a session behind this page. Only consulted for the statuses that are
// ambiguous on their own; see the 500 note below.
async function stillAuthenticated(): Promise<boolean> {
  try {
    const response = await fetch(SESSION_INFO_URL);
    if (!response.ok || isLoginRedirect(response)) {
      return false;
    }
    const info = await response.json() as { userID?: string };
    return Boolean(info.userID) && info.userID !== "anonymous";
  } catch {
    // The probe itself failed, so this says nothing about the session. Report it as live, so that
    // an unrelated network problem does not turn into a sign-in prompt.
    return true;
  }
}

// A `fetch` that survives the session expiring underneath it: rather than handing the caller a 401
// (or the login page, or one of Sling's 500s), it gets the user authenticated again and re-sends the
// request, resolving with the answer to the retried one. Callers see one pending promise across the
// whole round trip and need no session handling of their own.
//
// A 500 gets special treatment because Sling overloads it: writing to the repository with an expired
// session comes back as a 500 rather than a 401, so a 500 cannot simply be passed on as a server
// error -- that is exactly the case where recovering the session matters most. It cannot simply be
// treated as an expired session either, or a genuine server error would prompt for a sign-in that
// fixes nothing and, since the retry hits the same error, prompt again for as long as the user plays
// along. So a 500 is disambiguated by asking whether the session is still there, and only the
// answer to that decides between recovering and reporting.
export function useAuthenticatedFetch(): (url: string, init?: RequestInit) => Promise<Response> {
  const requestReLogin = useContext(ReLoginContext);

  return useCallback((url: string, init?: RequestInit) => new Promise<Response>((resolve, reject) => {
    const attempt = () => {
      fetch(url, init)
        .then(async response => {
          const expired = response.status === 401 || isLoginRedirect(response);
          // Ambiguous on its own, so it costs one extra request to tell the two apart
          if (!expired && !(response.status === 500 && !await stillAuthenticated())) {
            resolve(response);
            return;
          }
          if (!requestReLogin) {
            reject(new Error(`Not authenticated, and no sign-in is available: ${url}`));
            return;
          }
          if (await requestReLogin()) {
            attempt();
          } else {
            reject(new Error(`Not authenticated, and signing in was abandoned: ${url}`));
          }
        })
        .catch((err: unknown) => { reject(err instanceof Error ? err : new Error(String(err))); });
    };
    attempt();
  }), [requestReLogin]);
}
