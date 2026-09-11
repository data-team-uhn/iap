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

// What a notification is, where they live, and how to read one out of a listing row. No React, no
// fetch: everything here is a pure function of its arguments. The I/O that uses it is in
// useNotifications.
//
// A listing asks for its own recipient by name, and is read on the caller's own session, which can
// see nobody else's notification. Belt and braces on purpose: access control does not apply to an
// administrative session, and an administrator asking for everyone's would mark them all read.

import { type EntityRow } from "@iap/frontend-commons/entityGrid/pagination";

/** Where the stored notifications live. */
export const NOTIFICATIONS_PATH = "/Notifications";

/** The property naming who a notification is for. `@me` is resolved to the caller by the server. */
export const RECIPIENT = "recipient";

/** The property holding the read marker. A filter value of `false` matches an unread one. */
export const READ = "read";

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
 * Reads one notification out of a listing row.
 *
 * @param row one row of a `.paginate.json` listing
 * @returns what that row says, with anything unreadable left out
 */
export function parseNotification(row: EntityRow): Notification {
  return {
    path: String(row["@path"]),
    line: typeof row.line === "string" ? row.line : "",
    // A single-valued boolean may round-trip as a bare boolean or as a string, so both are accepted
    read: row.read === true || row.read === "true",
    subject: typeof row.subject === "string" ? row.subject : undefined,
    created: typeof row["jcr:created"] === "string" ? row["jcr:created"] : undefined,
  };
}

export function markReadUrl(notification: Notification): string {
  return `${notification.path}.markRead.json`;
}
