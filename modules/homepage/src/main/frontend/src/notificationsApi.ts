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

// Client for the stored notifications under /Notifications. There is no "mine" parameter anywhere:
// each notification is readable by exactly one account, its recipient, so listing on the caller's
// own session already answers "my notifications" - the repository did the filtering.

import { fetchEntityPage } from "@iap/frontend-commons/entityGrid/pagination";
import { type AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { RequestError } from "@iap/frontend-commons/requestFailure";

/** Where the stored notifications live. */
export const NOTIFICATIONS_PATH = "/Notifications";

/** One thing the current user was told. */
export interface Notification {
  /** The notification node itself, where the read marker is posted. */
  path: string;
  /** The rendered sentence to show. */
  line: string;
  /** Whether it has been seen before. */
  read: boolean;
  /** What it is about, to link to; possibly gone by now, which the deletion machinery explains. */
  subject?: string;
  /** When it was raised, as the repository serialized it. */
  created?: string;
}

/**
 * The current user's notifications, newest first.
 *
 * @param fetchUtil the session-aware fetch to go through
 * @param limit how many to fetch at most
 * @returns the notifications, empty when there are none
 * @throws RequestError if the server rejects the request, or whatever `fetch` failed with
 */
export async function fetchNotifications(
  fetchUtil: AuthenticatedFetch, limit = 100): Promise<Notification[]> {
  const page = await fetchEntityPage(fetchUtil, {
    homepage: NOTIFICATIONS_PATH,
    limit,
    sortBy: "jcr:created",
    descending: true,
  });
  return page.rows.map(row => ({
    path: String(row["@path"]),
    line: typeof row.line === "string" ? row.line : "",
    // A single-valued boolean may round-trip as a bare boolean or as a string, so both are accepted
    read: row.read === true || row.read === "true",
    subject: typeof row.subject === "string" ? row.subject : undefined,
    created: typeof row["jcr:created"] === "string" ? row["jcr:created"] : undefined,
  }));
}

/**
 * Marks one notification as read. The `.json` extension is not optional: Sling reads the last
 * dot-separated token as the extension, so a bare `.markRead` matches no servlet and falls through.
 *
 * @param fetchUtil the session-aware fetch to go through
 * @param path the notification node, as its rows report it
 * @throws RequestError if the server refuses
 */
export async function markRead(fetchUtil: AuthenticatedFetch, path: string): Promise<void> {
  const response = await fetchUtil(`${path}.markRead.json`, { method: "POST" });
  if (!response.ok) {
    throw new RequestError(response.status);
  }
}
