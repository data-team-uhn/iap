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

import { describeRequestFailure, RequestError } from "./requestFailure";

import type { PatchValue } from "./fields/fieldsModel";
import type { AuthenticatedFetch } from "./reLogin";

// Sends a workflow event to a node: POST <path>.<event>.json, and the engine decides what it does. A
// refusal carries the engine's reason, which is already worded for the person reading it, so it is
// passed on as it stands; anything else is described like any other failed request.
//
// The parameters go as a form, or as the FormData given, for an event that brings a file.
//
// Resolves with the path of what the event created, when it created something: the engine answers
// with a redirect, which fetch has already followed. The path is given as the repository names it,
// not percent-encoded as the URL has it, so it compares with the `@path` the serialization gives.
export async function sendEvent(
  doFetch: AuthenticatedFetch,
  path: string,
  event: string,
  params: Record<string, string> | FormData = {},
): Promise<string | undefined> {
  let response: Response;
  try {
    response = await doFetch(`${path}.${event}.json`, {
      method: "POST",
      body: params instanceof FormData ? params : new URLSearchParams(params),
    });
  } catch (error: unknown) {
    throw new Error(describeRequestFailure(error));
  }
  if (response.redirected) {
    return decodeURIComponent(new URL(response.url).pathname);
  }
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { error?: unknown };
    if (typeof body.error === "string" && body.error) {
      throw new RequestError(response.status, body.error);
    }
    throw new Error(describeRequestFailure(new RequestError(response.status)));
  }
  return undefined;
}

// Whether the server offers an event on a node it serialized with the `events` selector, which lists
// in `@events` the events the current user may send it: whether the event applies there, for them.
export const offers = (node: { "@events"?: unknown }, event: string): boolean =>
  Array.isArray(node["@events"]) && node["@events"].includes(event);

// An update event's payload: the changed fields, as one JSON object.
export const patch = (changes: Record<string, PatchValue>): Record<string, string> =>
  ({ patch: JSON.stringify(changes) });
