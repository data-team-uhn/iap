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

import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";

import type { Mock } from "vitest";

// A stand-in server for the tests: `stubFetch` replaces the global fetch with routes that answer
// with real responses. A request no route answers is rejected with an error naming it.

// One request the stubbed fetch received
export interface StubbedRequest {
  url: string;
  // In upper case, GET when the caller named none
  method: string;
  // The form fields of a body sent as URLSearchParams or as a string; empty for any other body
  params: URLSearchParams;
  // What the caller passed beside the URL; undefined if it passed nothing
  init?: RequestInit;
}

// The code under test passes its URLs as strings, and the recorded calls hold them as strings
export type FetchMock = Mock<(url: string, init?: RequestInit) => Promise<Response>>;

// How a route answers: with a response, or with a function of the request. A response is cloned
// for each request, since a body can be read only once.
export type FetchAnswer = Response | ((request: StubbedRequest) => Response | Promise<Response>);

// Answers keyed by URL, which then answer any method, or by method and URL: `"POST /Categories/x"`.
// The URL must match exactly, query included.
export type FetchRoutes = Record<string, FetchAnswer>;

// A route that decides for itself. It returns undefined to leave the request to the next route.
export type FetchRoute = (request: StubbedRequest) => Response | Promise<Response> | undefined;

// A response carrying `body` as JSON
export const jsonResponse = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

// What the session endpoint says about who is signed in. `anonymous` means nobody is.
export const sessionInfo = (userID: string): FetchRoutes => ({ [SESSION_INFO_URL]: jsonResponse({ userID }) });

const SIGNED_IN = sessionInfo("admin");

// Replaces the global fetch with `routes`, tried in order. Unless a route names the session
// endpoint, it answers first that somebody is signed in. After a 500, useAuthenticatedFetch asks it
// whether the session is still there, and this answer hands the 500 on to the caller.
export function stubFetch(...routes: (FetchRoutes | FetchRoute)[]): FetchMock {
  const answerers = [...routes.some(namesSession) ? [] : [SIGNED_IN], ...routes]
    .map(route => typeof route === "function" ? route : routeOf(route));
  const fetchMock: FetchMock = vi.fn((url: string, init?: RequestInit) => {
    const request = requestOf(url, init);
    for (const answer of answerers) {
      const response = answer(request);
      if (response) {
        return Promise.resolve(response);
      }
    }
    return Promise.reject(new Error(`No stubbed route answers ${request.method} ${request.url}`));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

// Replaces the global fetch with a server that fails every request with `status`. The response
// carries `body` as JSON if one is given, and is empty otherwise. The session endpoint still answers
// that somebody is signed in.
export function stubFailingFetch(
  status: number,
  { body, statusText }: { body?: unknown; statusText?: string } = {},
): FetchMock {
  const failure = new Response(body === undefined ? null : JSON.stringify(body), { status, statusText });
  return stubFetch(() => failure.clone());
}

// The requests the stubbed global fetch received, in order, the session endpoint's included
export const sentRequests = (): StubbedRequest[] =>
  vi.mocked(fetch).mock.calls.map(([input, init]) => requestOf(input, init));

const namesSession = (route: FetchRoutes | FetchRoute) =>
  typeof route !== "function" && Object.keys(route).some(key => key.endsWith(SESSION_INFO_URL));

function routeOf(routes: FetchRoutes): FetchRoute {
  const byKey = new Map(Object.entries(routes));
  return request => {
    const answer = byKey.get(`${request.method} ${request.url}`) ?? byKey.get(request.url);
    if (answer instanceof Response) {
      return answer.clone();
    }
    return answer?.(request);
  };
}

function requestOf(input: RequestInfo | URL, init?: RequestInit): StubbedRequest {
  const body = init?.body;
  return {
    url: input instanceof Request ? input.url : input.toString(),
    method: (init?.method ?? "GET").toUpperCase(),
    params: new URLSearchParams(body instanceof URLSearchParams || typeof body === "string" ? body : undefined),
    init,
  };
}
