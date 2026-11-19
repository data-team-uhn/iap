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

// Sends a workflow event to a node, as a POST to `<path>.<event>.json`. The engine decides what it
// does. The parameters go as a form, or as the FormData given, for an event that brings a file. A
// parameter with several values comes as URLSearchParams, which repeat it.
//
// It resolves with the path of the node the event created, as the engine's answer names it; undefined
// if the event created nothing. The path is the repository's, so it compares with the `@path` of a
// serialized node.
//
// A failure that carries the engine's reason rejects with a RequestError holding that reason, which
// is worded for the user. Any other failure rejects with an Error whose message is a sentence for the
// user.
export async function sendEvent(
  doFetch: AuthenticatedFetch,
  path: string,
  event: string,
  params: Record<string, string> | URLSearchParams | FormData = {},
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
  const answer = (await response.json().catch(() => ({}))) as { error?: unknown; path?: unknown };
  if (!response.ok) {
    if (typeof answer.error === "string" && answer.error) {
      throw new RequestError(response.status, answer.error);
    }
    throw new Error(describeRequestFailure(new RequestError(response.status)));
  }
  return typeof answer.path === "string" ? answer.path : undefined;
}

// Whether the current user may send the event to the node. The node must come from the `events`
// selector, which lists those events in `@events`; without it, no event is offered.
export const offers = (node: { "@events"?: unknown }, event: string): boolean =>
  Array.isArray(node["@events"]) && node["@events"].includes(event);

// An update event's payload: the changed fields, as one JSON object.
export const patch = (changes: Record<string, PatchValue>): Record<string, string> =>
  ({ patch: JSON.stringify(changes) });
