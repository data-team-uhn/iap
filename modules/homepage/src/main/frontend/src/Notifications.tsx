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

import NotificationsNoneIcon from "@mui/icons-material/NotificationsNone";
import { Badge, IconButton, ListItemText, Menu, MenuItem, Tooltip } from "@mui/material";
import { useNavigate } from "react-router";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { type Notification, fetchNotifications, markRead } from "./notificationsApi";

// How often the badge re-asks whether something happened. Nothing else in the interface polls, but
// nothing else announces events either: a badge that only updated on page loads would routinely be
// stale on the page somebody keeps open all day.
const REFRESH_MILLIS = 60_000;

// How many entries the dropdown shows; the rest are old news, still stored, just not listed here
const SHOWN = 10;

// The notifications bell in the app bar, revealing the current user's notifications as a dropdown.
// Opening the list is what "reading" means here: everything unread is marked read once shown, the
// same way glancing at a stack of letters takes them off the doormat. Registered on the
// `iap/appBar/entry` extension point, end section.
function Notifications() {
  const doFetch = useAuthenticatedFetch();
  const navigate = useNavigate();
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  const [ notifications, setNotifications ] = useState<Notification[]>([]);
  const [ unread, setUnread ] = useState(0);
  const [ failed, setFailed ] = useState(false);

  const refresh = useCallback(async () => {
    const recent = await fetchNotifications(doFetch);
    setNotifications(recent);
    setUnread(recent.filter(notification => !notification.read).length);
    setFailed(false);
    return recent;
  }, [doFetch]);

  useEffect(() => {
    // A badge that cannot be refreshed keeps its last value quietly; the next tick tries again
    const quietly = () => {
      void refresh().catch(() => undefined);
    };
    quietly();
    const timer = setInterval(quietly, REFRESH_MILLIS);
    return () => clearInterval(timer);
  }, [refresh]);

  const open = async (target: HTMLElement) => {
    setAnchor(target);
    try {
      const recent = await refresh();
      // Shown is read: the entries stay highlighted for this look, and stop counting from now on
      await Promise.all(recent.filter(notification => !notification.read)
        .map(notification => markRead(doFetch, notification.path)));
      setUnread(0);
    } catch {
      // The list may be mid-air when the session expires; the dropdown says so instead of lying
      setFailed(true);
    }
  };

  const follow = (notification: Notification) => {
    setAnchor(null);
    if (notification.subject) {
      void navigate(notification.subject);
    }
  };

  return (
    <>
      <Tooltip title="Notifications">
        <IconButton
          aria-label="Notifications"
          onClick={event => { void open(event.currentTarget); }}
          size="small"
        >
          { /* The badge hides itself while the count is 0 */ }
          <Badge badgeContent={unread} color="secondary">
            <NotificationsNoneIcon />
          </Badge>
        </IconButton>
      </Tooltip>
      <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}>
        { failed && <MenuItem disabled>The notifications could not be loaded</MenuItem> }
        { !failed && notifications.length === 0
          && <MenuItem disabled>You have no notifications</MenuItem> }
        { !failed && notifications.slice(0, SHOWN).map(notification => (
          <MenuItem
            key={notification.path}
            onClick={() => follow(notification)}
            disabled={!notification.subject}
          >
            <ListItemText
              primary={notification.line}
              secondary={notification.created && new Date(notification.created).toLocaleString()}
              slotProps={{ primary: { sx: { fontWeight: notification.read ? undefined : "bold" } } }}
            />
          </MenuItem>
        )) }
      </Menu>
    </>
  );
}

export default Notifications;
