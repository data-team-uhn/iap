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

import { useState } from "react";

import NotificationsNoneIcon from "@mui/icons-material/NotificationsNone";
import { Badge, IconButton, ListItemText, Menu, MenuItem, Tooltip } from "@mui/material";
import { useNavigate } from "react-router";

import { type Notification } from "./notificationsModel";
import { useNotifications } from "./useNotifications";

// How many entries the dropdown shows; the rest are old news, still stored, just not listed here
const SHOWN = 10;

// The notifications bell in the app bar, revealing the current user's notifications as a dropdown.
// Opening the list marks what it shows as read. Registered on the `iap/appBar/entry` extension
// point, end section.
function Notifications() {
  const navigate = useNavigate();
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  const { notifications, unreadCount, failed, read } = useNotifications();

  const open = (target: HTMLElement) => {
    setAnchor(target);
    void read();
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
          onClick={event => open(event.currentTarget)}
          size="small"
        >
          { /* Hides itself at 0, and stops at 99+ rather than widening to fit */ }
          <Badge badgeContent={unreadCount} max={99} color="secondary">
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
