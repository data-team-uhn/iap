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
import { Badge, IconButton, Menu, MenuItem, Tooltip } from "@mui/material";

// The notifications bell in the app bar, revealing the list of notifications as a dropdown.
// There is no notification mechanism yet, so for now the list is always empty and the badge
// hidden; the control establishes the UI so notifications have a home when they arrive.
// Registered on the `iap/appBar/entry` extension point, end section.
function Notifications() {
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  const notificationCount = 0;

  return (
    <>
      <Tooltip title="Notifications">
        <IconButton
          aria-label="Notifications"
          onClick={event => setAnchor(event.currentTarget)}
          size="small"
        >
          { /* The badge hides itself while the count is 0 */ }
          <Badge badgeContent={notificationCount} color="secondary">
            <NotificationsNoneIcon />
          </Badge>
        </IconButton>
      </Tooltip>
      <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}>
        <MenuItem disabled>You have no new notifications</MenuItem>
      </Menu>
    </>
  );
}

export default Notifications;
