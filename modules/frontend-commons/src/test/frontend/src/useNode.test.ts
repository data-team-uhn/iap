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

import { readNode, useNode } from "@iap/frontend-commons/useNode";

type FetchStub = (url: string) => Promise<Response>;

// A server answering every request with the given body and status. Each answer carries the `url` it
// came back from, which useAuthenticatedFetch reads to tell a response from the login page.
const answering = (body: unknown, status = 200) => vi.fn<FetchStub>(url => Promise.resolve({
  ok: status < 400, status, url, json: () => Promise.resolve(body),
} as unknown as Response));

// Defined once rather than inline, since a new parser on every render would be a new read every time
const titleOf = (node: Record<string, unknown>) => node.title;

describe("readNode", () => {
  it("asks for the node through the selectors given", async () => {
    const doFetch = answering({ title: "Consent" });

    await expect(readNode(doFetch, "/Schemas/consent", "1.simple")).resolves.toEqual({ title: "Consent" });
    expect(doFetch).toHaveBeenCalledWith("/Schemas/consent.1.simple.json");
  });

  // A refusal answers with an error page rather than with JSON, so the status has to be read before
  // the body: parsing first reports how the body disappointed the parser, not what was refused.
  it("words a refusal from its status, without reading the body", async () => {
    const json = vi.fn().mockRejectedValue(new SyntaxError("Unexpected token '<'"));
    const doFetch = vi.fn<FetchStub>(() => Promise.resolve({ ok: false, status: 403, json } as unknown as Response));

    await expect(readNode(doFetch, "/Schemas/consent", "1")).rejects.toThrow("You do not have permission");
    expect(json).not.toHaveBeenCalled();
  });

  it("words a request that never completed", async () => {
    const doFetch = vi.fn<FetchStub>(() => Promise.reject(new TypeError("Failed to fetch")));

    await expect(readNode(doFetch, "/Schemas/consent", "1")).rejects.toThrow("could not be reached");
  });
});

describe("useNode", () => {
  afterEach(() => { vi.unstubAllGlobals(); });

  it("reads the node once, and hands over what the caller makes of it", async () => {
    const fetch = answering({ title: "Consent" });
    vi.stubGlobal("fetch", fetch);
    const { result } = renderHook(() => useNode("/Schemas/consent", "1", titleOf));

    expect(result.current.loading).toBe(true);
    await waitFor(() => { expect(result.current.loading).toBe(false); });
    expect(result.current.value).toBe("Consent");
    expect(result.current.loadError).toBeUndefined();
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("keeps what it last read when reading again fails, so the page stays readable under the error", async () => {
    vi.stubGlobal("fetch", answering({ title: "Consent" }));
    const { result } = renderHook(() => useNode("/Schemas/consent", "1", titleOf));
    await waitFor(() => { expect(result.current.value).toBe("Consent"); });

    vi.stubGlobal("fetch", answering(null, 403));
    await act(() => result.current.reload());

    expect(result.current.value).toBe("Consent");
    expect(result.current.loadError).toMatch("You do not have permission");
  });

  it("is done loading when the first read fails, and drops the failure once a read succeeds", async () => {
    vi.stubGlobal("fetch", answering(null, 404));
    const { result } = renderHook(() => useNode("/Schemas/consent", "1", titleOf));
    await waitFor(() => { expect(result.current.loading).toBe(false); });
    expect(result.current.value).toBeUndefined();
    expect(result.current.loadError).toMatch("could not be found");

    vi.stubGlobal("fetch", answering({ title: "Consent" }));
    await act(() => result.current.reload());

    expect(result.current.value).toBe("Consent");
    expect(result.current.loadError).toBeUndefined();
  });
});
