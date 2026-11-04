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

import { isLoginRedirect, isNotAuthenticated, NotAuthenticatedError } from "./reLogin";

// Thrown when the server answered, but not with success. The status is kept so the failure can be
// described in the user's terms rather than the protocol's; the reason phrase is deliberately not,
// since HTTP/2 drops it and it would render as a dangling blank.
export class RequestError extends Error {
  readonly status: number;

  // Why the server refused, when it said
  readonly reason?: string;

  constructor(status: number, reason?: string) {
    super(reason ?? `HTTP ${status}`);
    this.name = "RequestError";
    this.status = status;
    this.reason = reason;
  }
}

// Whether the server refused and said why. Asking again the same way fails until something changes.
// A server error may pass, and a failure without a reason counts as one that may be retried.
export const isRefusal = (error: unknown): boolean =>
  error instanceof RequestError && error.reason !== undefined && error.status < 500;

const UNREADABLE_RESPONSE = "The server's response could not be read. Reload and try again.";

const SESSION_EXPIRED = "Your session has expired. Sign in again, then retry.";

// Thrown when a response that should have carried data carried a page instead, such as a proxy's
// page served as a success. The message is for the user, since some screens show it as it stands.
export class UnreadableResponseError extends Error {
  constructor() {
    super(UNREADABLE_RESPONSE);
    this.name = "UnreadableResponseError";
  }
}

// The JSON body of a response that should carry data. Sling's login page, after the session has
// gone, fails as an expired session; any other page fails as UnreadableResponseError instead of the
// parser's "Unexpected token '<'". A connection lost mid-body still fails as a connection failure.
export async function readJson<T>(response: Response): Promise<T> {
  if (isLoginRedirect(response)) {
    throw new NotAuthenticatedError(`Not authenticated: ${response.url}`);
  }
  try {
    return (await response.json()) as T;
  } catch (error) {
    if (!(error instanceof SyntaxError)) {
      throw error;
    }
    // The page itself is in the browser's network panel
    console.warn(`Expected data, got a page (HTTP ${response.status})`, response.url);
    throw new UnreadableResponseError();
  }
}

// What a rejection has to say for itself. Anything can be thrown, so the many places that end up
// having to display one need this, and none of them need to care that it is not always an Error.
export const messageOf = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

// Says why a request failed, in one sentence a user can act on: the technical detail they would
// have to relay to a developer is kept, in parentheses, but does not lead.
//
// The sentence describes the cause only, never what was being attempted - the screen or dialog
// asking for it already says that, and repeating it here would read as an echo. Failures that are
// worth diagnosing are logged, so the original stays reachable in the browser console.
const describe = (error: unknown): string => {
  // fetch rejects with a TypeError when the request never completed: no server, no route, no DNS
  if (error instanceof TypeError) {
    return typeof navigator !== "undefined" && !navigator.onLine
      ? "You appear to be offline. Check your connection, then try again."
      : "The server could not be reached. It may be restarting, or the connection may have dropped. "
        + "Try again in a moment.";
  }
  if (isNotAuthenticated(error)) {
    return SESSION_EXPIRED;
  }
  if (error instanceof UnreadableResponseError || error instanceof SyntaxError) {
    return UNREADABLE_RESPONSE;
  }
  if (error instanceof RequestError && error.reason) {
    return error.reason;
  }
  if (error instanceof RequestError) {
    switch (error.status) {
      case 401:
        return SESSION_EXPIRED;
      case 403:
        return `You do not have permission to do this. (HTTP ${error.status})`;
      case 404:
        return "It could not be found on the server - someone may have deleted or moved it. "
          + `(HTTP ${error.status})`;
      case 409:
        return `This conflicts with a more recent change. Reload and try again. (HTTP ${error.status})`;
      default:
        return error.status >= 500
          ? "The server ran into a problem and could not complete this. Try again in a moment. "
            + `(HTTP ${error.status})`
          : `The server rejected this. (HTTP ${error.status})`;
    }
  }
  return `Something went wrong: ${messageOf(error)}`;
};

export const describeRequestFailure = (error: unknown): string => {
  console.error("A request failed", error);
  return describe(error);
};
