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
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { UPLOAD_LIMITS_URL, fetchUploadLimits } from "@iap/frontend-commons/uploadLimits";

function answering(body: unknown, status = 200) {
  return vi.fn(() => Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response));
}

describe("fetchUploadLimits", () => {
  beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
  });

  afterEach(() => vi.restoreAllMocks());

  it("reads the server's limits", async () => {
    const doFetch = answering({ maxFileSize: 1000, maxPdfPages: 20, maxUnzippedSize: 3000 });

    expect(await fetchUploadLimits(doFetch)).toEqual({ maxFileSize: 1000, maxPdfPages: 20, maxUnzippedSize: 3000 });
    expect(doFetch).toHaveBeenCalledWith(UPLOAD_LIMITS_URL, { signal: undefined });
  });

  it("lets the caller stop the request", async () => {
    const doFetch = answering({ maxFileSize: 1000 });
    const signal = new AbortController().signal;

    await fetchUploadLimits(doFetch, signal);

    expect(doFetch).toHaveBeenCalledWith(UPLOAD_LIMITS_URL, { signal });
  });

  it("leaves out a limit that is missing or not a positive number", async () => {
    const doFetch = answering({ maxFileSize: "1000", maxPdfPages: 0, maxUnzippedSize: -1 });

    expect(await fetchUploadLimits(doFetch)).toEqual({});
  });

  it("leaves them all out when the server refuses", async () => {
    expect(await fetchUploadLimits(answering({ error: "No" }, 500))).toEqual({});
    expect(console.error).toHaveBeenCalled();
  });

  it("leaves them all out when the server cannot be reached", async () => {
    const doFetch = vi.fn(() => Promise.reject(new TypeError("Failed to fetch")));

    expect(await fetchUploadLimits(doFetch)).toEqual({});
    expect(console.error).toHaveBeenCalled();
  });
});
