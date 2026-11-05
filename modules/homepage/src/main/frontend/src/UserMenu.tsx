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

import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import { readJson } from "@iap/frontend-commons/requestFailure";

import PersonaSwitcher from "./PersonaSwitcher";

// Sling's user manager, which serves a user's properties as JSON
const userInfoUrl = (userId: string) => `/system/userManager/user/${encodeURIComponent(userId)}.json`;
// Sling's logout endpoint. Loading it ends the session.
const LOGOUT_URL = "/system/sling/logout";

// The user's full name: their `displayName`, or else their first and last names, from
// `firstname` or `givenName` and `lastname` or `familyName`. Empty if none is set.
const fullNameOf = (user: Record<string, unknown>): string =>
  ((user.displayName as string | undefined) ?? "").trim()
  || [ user.firstname ?? user.givenName, user.lastname ?? user.familyName ]
    .filter(Boolean).join(" ").trim();

// The first letters of a name's first and last words, in upper case. Empty for a blank name.
const initialsOf = (name: string): string => {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return "";
  }
  const initials = words[0][0] + (words.length > 1 ? words[words.length - 1][0] : "");
  return initials.toUpperCase();
};

// The current user's avatar, with the initials of their full name, or of their username without
// one. It opens a menu with the username, and the full name where it differs. The menu also has
// the PersonaSwitcher and a link to sign out. Nothing is rendered until the user is known.
function UserMenu() {
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  const [ userName, setUserName ] = useState("");
  const [ fullName, setFullName ] = useState("");

  useEffect(() => {
    fetch(SESSION_INFO_URL)
      .then(response => response.ok ? readJson<{ userID?: string }>(response) : Promise.reject(new Error(`Failed to load session info: ${response.status}`)))
      .then(json => setUserName(json.userID ?? ""))
      .catch((err: unknown) => console.error("Something went wrong identifying the current user", err));
  }, []);

  useEffect(() => {
    if (!userName) {
      return;
    }
    fetch(userInfoUrl(userName))
      .then(response => response.ok ? readJson<Record<string, unknown>>(response) : Promise.reject(new Error(`Failed to load user info: ${response.status}`)))
      .then(json => setFullName(fullNameOf(json)))
      // Without the user's properties, the menu shows only the username
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
            <Typography variant="description">{fullName}</Typography>
          )}
        </Box>
        <Divider sx={{ mb: 1 }} />
        <PersonaSwitcher onChoose={() => setAnchor(null)} />
        <MenuItem component="a" href={LOGOUT_URL}>
          <ListItemIcon><LogoutIcon fontSize="small" /></ListItemIcon>
          <ListItemText>Sign out</ListItemText>
        </MenuItem>
      </Menu>
    </>
  );
}

export default UserMenu;
