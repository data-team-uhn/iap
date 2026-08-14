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

// Thrown when the server answered, but not with success. The status is kept so the failure can be
// described in the user's terms rather than the protocol's; the reason phrase is deliberately not,
// since HTTP/2 drops it and it would render as a dangling blank.
export class RequestError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`HTTP ${status}`);
    this.name = "RequestError";
    this.status = status;
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
  if (error instanceof SyntaxError) {
    return "The server's response could not be read.";
  }
  if (error instanceof RequestError) {
    switch (error.status) {
      case 401:
        return "Your session has expired. Sign in again, then retry.";
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
