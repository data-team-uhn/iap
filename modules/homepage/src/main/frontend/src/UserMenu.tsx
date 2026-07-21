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

import { useEffect, useState } from "react";

import LogoutIcon from "@mui/icons-material/Logout";
import {
  Avatar,
  Box,
  Divider,
  IconButton,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Tooltip,
  Typography,
} from "@mui/material";

// Sling's session endpoint, reporting who the current session is authenticated as
const SESSION_INFO_URL = "/system/sling/info.sessionInfo.json";
// Sling's user management endpoint, serving a user's properties
const userInfoUrl = (userId: string) => `/system/userManager/user/${encodeURIComponent(userId)}.json`;
// Sling's logout endpoint; navigating to it ends the session
const LOGOUT_URL = "/system/sling/logout";

// The user's displayable full name, from whichever conventional properties their profile carries.
const fullNameOf = (user: Record<string, unknown>): string =>
  String(user.displayName ?? "").trim()
  || [ user.firstname ?? user.givenName, user.lastname ?? user.familyName ]
    .filter(Boolean).join(" ").trim();

// One or two initials identifying the user: the first letters of the first and last words of
// their full name (falling back to their user name).
const initialsOf = (name: string): string => {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return "";
  }
  const initials = words[0][0] + (words.length > 1 ? words[words.length - 1][0] : "");
  return initials.toUpperCase();
};

// The current user's presence in the app bar: an avatar with their initials, opening a menu that
// identifies the account (user name and, when the profile provides one, full name) and offers to
// sign out. Registered on the `iap/appBar/entry` extension point, end section.
function UserMenu() {
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  const [ userName, setUserName ] = useState("");
  const [ fullName, setFullName ] = useState("");

  useEffect(() => {
    fetch(SESSION_INFO_URL)
      .then(response => response.ok ? response.json() : Promise.reject(response))
      .then(json => setUserName(String(json.userID ?? "")))
      .catch(err => console.error("Something went wrong identifying the current user", err));
  }, []);

  useEffect(() => {
    if (!userName) {
      return;
    }
    fetch(userInfoUrl(userName))
      .then(response => response.ok ? response.json() : Promise.reject(response))
      .then(json => setFullName(fullNameOf(json)))
      // The full name is a nice-to-have; without it the menu just shows the user name
      .catch(() => setFullName(""));
  }, [ userName ]);

  if (!userName) {
    return null;
  }

  return (
    <>
      <Tooltip title={userName}>
        <IconButton
          aria-label={`Account: ${userName}`}
          onClick={event => setAnchor(event.currentTarget)}
          size="small"
        >
          <Avatar sx={{ width: 30, height: 30, bgcolor: "primary.main", fontSize: "0.875rem" }}>
            {initialsOf(fullName || userName)}
          </Avatar>
        </IconButton>
      </Tooltip>
      <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}>
        <Box sx={{ px: 2, py: 1 }}>
          <Typography variant="subtitle2">{userName}</Typography>
          {fullName && fullName !== userName && (
            <Typography variant="body2" color="text.secondary">{fullName}</Typography>
          )}
        </Box>
        <Divider sx={{ mb: 1 }} />
        <MenuItem component="a" href={LOGOUT_URL}>
          <ListItemIcon><LogoutIcon fontSize="small" /></ListItemIcon>
          <ListItemText>Sign out</ListItemText>
        </MenuItem>
      </Menu>
    </>
  );
}

export default UserMenu;
