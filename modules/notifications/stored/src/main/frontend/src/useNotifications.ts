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
  markReadUrl,
  type Notification,
  NOTIFICATIONS_PATH,
  READ,
  parseNotification,
  RECIPIENT,
  SHOWN,
} from "./notificationsModel";

// The requests of the notification bell: what it asks for, how often, and how it marks
// notifications read.

// How often the bell asks again, so that a page left open does not show a stale count
const REFRESH_MILLIS = 60_000;

/** How many notifications to ask for. The dropdown shows fewer. */
const LIMIT = 100;

/**
 * The current user's notifications, newest first. It asks for the caller's own by name, as well as
 * reading them on the caller's session. Access control does not apply to an administrator, whose
 * dropdown would otherwise list, and mark read, everybody's notifications.
 */
const list = async (fetchUtil: AuthenticatedFetch): Promise<Notification[]> => {
  const page = await fetchEntityPage(fetchUtil, {
    homepage: NOTIFICATIONS_PATH,
    limit: LIMIT,
    sortBy: "jcr:created",
    descending: true,
    filters: [ { name: RECIPIENT, value: "@me" } ],
  });
  return page.rows.map(parseNotification);
};

/**
 * How many of the current user's notifications are unread, as the server counts them. The list
 * holds only the newest page, and an unread notification can be older than that. It asks for one
 * row and reads the total.
 */
const countUnread = async (fetchUtil: AuthenticatedFetch): Promise<number> => {
  const page = await fetchEntityPage(fetchUtil, {
    homepage: NOTIFICATIONS_PATH,
    limit: 1,
    filters: [ { name: RECIPIENT, value: "@me" }, { name: READ, value: "false" } ],
  });
  return page.totalrows;
};

/** Marks one notification as read, or rejects with the HTTP status of the refusal. */
const mark = async (fetchUtil: AuthenticatedFetch, notification: Notification): Promise<void> => {
  const response = await fetchUtil(markReadUrl(notification), { method: "POST" });
  if (!response.ok) {
    throw new RequestError(response.status);
  }
};

export interface NotificationsFeed {
  /** The current user's notifications, newest first. */
  notifications: Notification[];
  /** How many of their notifications are unread, including those not in the list. */
  unreadCount: number;
  /** Whether the list failed to load when it was last opened. The next load that succeeds clears it. */
  failed: boolean;
  /** Loads the list again, then marks as read the unread ones the dropdown shows. */
  read: () => Promise<void>;
}

/**
 * The current user's notifications and unread count, asked for again every minute while the page
 * is open.
 *
 * Opening the list marks as read the unread notifications it shows. `read` does both in one call,
 * so that the count and the list cannot disagree.
 *
 * @returns the notifications, the unread count, whether loading failed, and `read`
 */
export function useNotifications(): NotificationsFeed {
  const doFetch = useAuthenticatedFetch();
  const [ notifications, setNotifications ] = useState<Notification[]>([]);
  const [ unreadCount, setUnreadCount ] = useState(0);
  const [ failed, setFailed ] = useState(false);

  const refresh = useCallback(async (): Promise<Notification[]> => {
    const [ recent, unread ] = await Promise.all([ list(doFetch), countUnread(doFetch) ]);
    setNotifications(recent);
    setUnreadCount(unread);
    setFailed(false);
    return recent;
  }, [ doFetch ]);

  useEffect(() => {
    // A count that cannot be loaded keeps its last value until the next try
    const quietly = () => {
      void refresh().catch(() => undefined);
    };
    quietly();
    const timer = setInterval(quietly, REFRESH_MILLIS);
    return () => clearInterval(timer);
  }, [ refresh ]);

  const read = useCallback(async (): Promise<void> => {
    let recent: Notification[];
    try {
      recent = await refresh();
    } catch {
      // The dropdown says the list could not be loaded, instead of looking empty
      setFailed(true);
      return;
    }
    try {
      // Only those the dropdown shows. Nothing can mark a notification unread again.
      await Promise.all(recent.slice(0, SHOWN).filter(notification => !notification.read)
        .map(notification => mark(doFetch, notification)));
      setUnreadCount(await countUnread(doFetch));
    } catch {
      // The list loaded, and only marking failed, so the list stays on screen
      setUnreadCount(await countUnread(doFetch));
    }
  }, [ doFetch, refresh ]);

  return { notifications, unreadCount, failed, read };
}
