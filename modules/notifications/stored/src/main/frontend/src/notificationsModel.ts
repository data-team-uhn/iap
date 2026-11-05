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

// What a stored notification is, where they live, and how to read one out of a listing row. The
// requests are in useNotifications.

import { type EntityRow } from "@iap/frontend-commons/entityGrid/pagination";

/** Where the stored notifications live. */
export const NOTIFICATIONS_PATH = "/Notifications";

/** The property naming who a notification is for. `@me` is resolved to the caller by the server. */
export const RECIPIENT = "recipient";

/** The property holding the read marker. A filter value of `false` matches an unread one. */
export const READ = "read";

/**
 * How many notifications the dropdown shows. Opening it marks those it shows as read, so the markup
 * and the marking both use this one count.
 */
export const SHOWN = 10;

/** One thing the current user was told. */
export interface Notification {
  /** The path of the notification, where the read marker is posted. */
  path: string;
  /** The rendered message to show. */
  message: string;
  /** Whether the recipient has marked it read. */
  read: boolean;
  /** The path of what it is about, to link to. It may have been deleted since. */
  subject?: string;
  /** When it was stored, as an ISO 8601 date and time. */
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
    message: typeof row.message === "string" ? row.message : "",
    // Accepts the read marker as a boolean or as the string "true"
    read: row.read === true || row.read === "true",
    subject: typeof row.subject === "string" ? row.subject : undefined,
    created: typeof row["jcr:created"] === "string" ? row["jcr:created"] : undefined,
  };
}

export function markReadUrl(notification: Notification): string {
  return `${notification.path}.markRead.json`;
}
