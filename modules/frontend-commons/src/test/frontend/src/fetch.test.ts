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

import {
  jsonResponse,
  sentRequests,
  sessionInfo,
  stubFailingFetch,
  stubFetch,
} from "@iap/frontend-commons/fetch.fixture";
import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";

afterEach(() => vi.unstubAllGlobals());

const bodyOf = async (url: string, init?: RequestInit) => (await fetch(url, init)).json() as Promise<unknown>;

describe("jsonResponse", () => {
  it("carries its body as JSON, with a success status by default", async () => {
    const response = jsonResponse({ answer: 42 });

    expect(response.status).toBe(200);
    expect(response.ok).toBe(true);
    expect(response.headers.get("Content-Type")).toBe("application/json");
    expect(await response.json()).toEqual({ answer: 42 });
  });

  it("takes the status it is given", async () => {
    const response = jsonResponse({ error: "No" }, 409);

    expect(response.status).toBe(409);
    expect(response.ok).toBe(false);
    expect(await response.json()).toEqual({ error: "No" });
  });
});

describe("stubFetch", () => {
  it("answers a URL with its route, afresh on every request", async () => {
    stubFetch({ "/a.json": jsonResponse("a"), "/b.json": jsonResponse("b") });

    expect(await bodyOf("/b.json")).toBe("b");
    expect(await bodyOf("/a.json")).toBe("a");
    expect(await bodyOf("/a.json")).toBe("a");
  });

  it("answers a method and URL only for that method", async () => {
    stubFetch({ "POST /a": jsonResponse("posted"), "/a": jsonResponse("read") });

    expect(await bodyOf("/a", { method: "post" })).toBe("posted");
    expect(await bodyOf("/a")).toBe("read");
  });

  it("calls a function answer with the request", async () => {
    stubFetch({ "POST /a": request => jsonResponse(request.params.get("name")) });

    expect(await bodyOf("/a", { method: "POST", body: new URLSearchParams({ name: "ann" }) })).toBe("ann");
  });

  it("tries the routes in order, a function route passing on what it leaves undefined", async () => {
    stubFetch(
      request => request.url.startsWith("/a") ? jsonResponse("first") : undefined,
      { "/a.json": jsonResponse("shadowed"), "/b.json": jsonResponse("second") },
    );

    expect(await bodyOf("/a.json")).toBe("first");
    expect(await bodyOf("/b.json")).toBe("second");
  });

  it("passes on a rejection a function route returns", async () => {
    stubFetch(() => Promise.reject(new TypeError("Failed to fetch")));

    await expect(fetch("/a")).rejects.toThrow("Failed to fetch");
  });

  it("reports somebody signed in unless told otherwise", async () => {
    stubFetch();

    expect(await bodyOf(SESSION_INFO_URL)).toEqual({ userID: "admin" });
  });

  it("lets a route answer for the session", async () => {
    stubFetch(sessionInfo("anonymous"));

    expect(await bodyOf(SESSION_INFO_URL)).toEqual({ userID: "anonymous" });
  });

  it("rejects a request no route answers, naming it", async () => {
    stubFetch({ "/a": jsonResponse("a") });

    await expect(fetch("/b", { method: "DELETE" })).rejects.toThrow("No stubbed route answers DELETE /b");
  });

  it("replaces the global fetch with the mock it returns", async () => {
    const fetchMock = stubFetch({ "/a": jsonResponse("a") });

    await fetch("/a");

    expect(fetch).toBe(fetchMock);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("stubFailingFetch", () => {
  it("fails every request with the status, and no body", async () => {
    stubFailingFetch(503);

    const response = await fetch("/a");
    expect(response.status).toBe(503);
    expect(await response.text()).toBe("");
    expect((await fetch("/b", { method: "POST" })).status).toBe(503);
  });

  it("carries the body and reason phrase it is given", async () => {
    stubFailingFetch(409, { body: { error: "Stale" }, statusText: "Conflict" });

    const first = await fetch("/a");
    expect(first.statusText).toBe("Conflict");
    expect(await first.json()).toEqual({ error: "Stale" });
    expect(await bodyOf("/a")).toEqual({ error: "Stale" });
  });

  it("still reports somebody signed in", async () => {
    stubFailingFetch(500);

    expect(await bodyOf(SESSION_INFO_URL)).toEqual({ userID: "admin" });
  });
});

describe("sentRequests", () => {
  it("lists every request in order, with its method, form fields and options", async () => {
    stubFetch(() => jsonResponse({}));
    const init = { method: "post", body: new URLSearchParams({ name: "ann" }) };

    await fetch("/a.json");
    await fetch(new URL("http://localhost/b"), init);
    await fetch("/c", { method: "PUT", body: "x=1&y=2" });
    const upload = new FormData();
    upload.append("x", "1");
    await fetch("/d", { method: "PUT", body: upload });
    await fetch(new Request("http://localhost/e"));

    const requests = sentRequests();
    expect(requests.map(request => `${request.method} ${request.url}`))
      .toEqual([ "GET /a.json", "POST http://localhost/b", "PUT /c", "PUT /d", "GET http://localhost/e" ]);
    expect(requests[0].init).toBeUndefined();
    expect(requests[1].init).toBe(init);
    expect(requests[1].params.get("name")).toBe("ann");
    expect([ ...requests[2].params.keys() ]).toEqual([ "x", "y" ]);
    expect([ ...requests[3].params.keys() ]).toEqual([]);
  });
});
