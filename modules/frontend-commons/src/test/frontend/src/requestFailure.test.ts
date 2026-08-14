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

import { describeRequestFailure, messageOf, RequestError } from "@iap/frontend-commons/requestFailure";

// Every description is logged with the original, so the raw failure stays reachable in the console
let logged: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  logged = vi.spyOn(console, "error").mockImplementation(() => { /* kept out of the test output */ });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("RequestError", () => {
  it("carries the status, and no reason phrase", () => {
    const error = new RequestError(503);

    expect(error.status).toBe(503);
    // HTTP/2 drops the reason phrase, so it is never part of what a user might be shown
    expect(error.message).toBe("HTTP 503");
    expect(error).toBeInstanceOf(Error);
  });
});

describe("messageOf", () => {
  it("takes an Error at its word", () => {
    expect(messageOf(new Error("something specific"))).toBe("something specific");
  });

  it("makes do with whatever else was thrown", () => {
    expect(messageOf("connection reset")).toBe("connection reset");
    expect(messageOf(undefined)).toBe("undefined");
    expect(messageOf({ toString: () => "an object with opinions" })).toBe("an object with opinions");
  });

  it("says nothing of its own when an Error carries no message", () => {
    expect(messageOf(new Error())).toBe("");
  });
});

describe("describeRequestFailure", () => {
  it("logs the original alongside whatever it says", () => {
    const original = new RequestError(500);

    describeRequestFailure(original);

    expect(logged).toHaveBeenCalledWith("A request failed", original);
  });

  describe("when the request never completed", () => {
    it("blames the connection when the browser reports being offline", () => {
      vi.stubGlobal("navigator", { onLine: false });

      expect(describeRequestFailure(new TypeError("Failed to fetch")))
        .toBe("You appear to be offline. Check your connection, then try again.");
    });

    it("blames the server when the browser believes it is online", () => {
      vi.stubGlobal("navigator", { onLine: true });

      const message = describeRequestFailure(new TypeError("Failed to fetch"));

      expect(message).toContain("The server could not be reached");
      expect(message).toContain("Try again in a moment.");
      // The browser's own wording, which differs per browser, is not passed on
      expect(message).not.toContain("Failed to fetch");
    });
  });

  it("says a response could not be read when it did not parse", () => {
    expect(describeRequestFailure(new SyntaxError("Unexpected token < in JSON at position 0")))
      .toBe("The server's response could not be read.");
  });

  describe("when the server answered with a status", () => {
    // The status is kept in every case it is known, so it can be quoted in a bug report - except
    // for the expired session, where the user's next step is all that matters
    it.each([
      [401, "Your session has expired. Sign in again, then retry."],
      [403, "You do not have permission to do this. (HTTP 403)"],
      [404, "It could not be found on the server - someone may have deleted or moved it. (HTTP 404)"],
      [409, "This conflicts with a more recent change. Reload and try again. (HTTP 409)"],
      [400, "The server rejected this. (HTTP 400)"],
      [418, "The server rejected this. (HTTP 418)"],
    ])("describes %i", (status, expected) => {
      expect(describeRequestFailure(new RequestError(status))).toBe(expected);
    });

    it.each([500, 502, 503])("blames the server for %i, and suggests waiting", status => {
      const message = describeRequestFailure(new RequestError(status));

      expect(message).toContain("The server ran into a problem");
      expect(message).toContain(`(HTTP ${status})`);
    });
  });

  describe("when the failure is none of the above", () => {
    it("passes on an ordinary Error's message", () => {
      expect(describeRequestFailure(new Error("something specific")))
        .toBe("Something went wrong: something specific");
    });

    it("copes with a rejection that was not an Error at all", () => {
      expect(describeRequestFailure("connection reset"))
        .toBe("Something went wrong: connection reset");
    });
  });
});
