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

import { useCallback, useEffect, useState } from "react";

import { fetchEntityPage } from "@iap/frontend-commons/entityGrid/pagination";
import { type AuthenticatedFetch, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { RequestError } from "@iap/frontend-commons/requestFailure";

import {
  countUnread,
  markReadUrl,
  type Notification,
  NOTIFICATIONS_PATH,
  parseNotification,
} from "./notificationsModel";

// The notification bell's I/O, in one place: what it polls for, how often, and what marking
// something read amounts to. Parsing lives in notificationsModel.

// How often the badge re-asks whether something happened. Nothing else in the interface polls, and
// nothing else announces events either. A badge that only updated on page loads would be stale all
// day on a page somebody keeps open.
const REFRESH_MILLIS = 60_000;

/** How many to ask for. The dropdown shows fewer. */
const LIMIT = 100;

/** The current user's notifications, newest first. */
const list = async (fetchUtil: AuthenticatedFetch): Promise<Notification[]> => {
  const page = await fetchEntityPage(fetchUtil, {
    homepage: NOTIFICATIONS_PATH,
    limit: LIMIT,
    sortBy: "jcr:created",
    descending: true,
  });
  return page.rows.map(parseNotification);
};

/** Marks one notification as read, or rejects with the status the server refused it with. */
const mark = async (fetchUtil: AuthenticatedFetch, path: string): Promise<void> => {
  const response = await fetchUtil(markReadUrl(path), { method: "POST" });
  if (!response.ok) {
    throw new RequestError(response.status);
  }
};

export interface NotificationsFeed {
  /** What the current user was told, newest first. */
  notifications: Notification[];
  /** How many of those have not been seen. */
  unread: number;
  /** True when the last attempt failed, so the dropdown can say so rather than look empty. */
  failed: boolean;
  /** Re-reads, then marks everything now showing as read. Resolves once it has settled. */
  read: () => Promise<void>;
}

/**
 * The bell's notifications, polled while the page is open.
 *
 * Opening the list is what reading means here: everything unread is marked read once shown, like
 * a glance at the doormat taking the letters off it. It is one operation, not a read and a write
 * for a caller to sequence. Doing half of it leaves the badge disagreeing with the list under it.
 *
 * @returns the feed and the one operation a caller performs on it
 */
export function useNotifications(): NotificationsFeed {
  const doFetch = useAuthenticatedFetch();
  const [ notifications, setNotifications ] = useState<Notification[]>([]);
  const [ unread, setUnread ] = useState(0);
  const [ failed, setFailed ] = useState(false);

  const refresh = useCallback(async (): Promise<Notification[]> => {
    const recent = await list(doFetch);
    setNotifications(recent);
    setUnread(countUnread(recent));
    setFailed(false);
    return recent;
  }, [ doFetch ]);

  useEffect(() => {
    // A badge that cannot be refreshed keeps its last value quietly; the next tick tries again
    const quietly = () => {
      void refresh().catch(() => undefined);
    };
    quietly();
    const timer = setInterval(quietly, REFRESH_MILLIS);
    return () => clearInterval(timer);
  }, [ refresh ]);

  const read = useCallback(async (): Promise<void> => {
    try {
      const recent = await refresh();
      // Shown is read: the entries stay highlighted for this look, and stop counting from now on
      await Promise.all(recent.filter(notification => !notification.read)
        .map(notification => mark(doFetch, notification.path)));
      setUnread(0);
    } catch {
      // The list may be mid-air when the session expires; the dropdown says so instead of lying
      setFailed(true);
    }
  }, [ doFetch, refresh ]);

  return { notifications, unread, failed, read };
}
