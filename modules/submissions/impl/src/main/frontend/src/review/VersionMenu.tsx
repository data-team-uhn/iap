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

import ArrowDropDownOutlined from "@mui/icons-material/ArrowDropDownOutlined";
import CheckOutlined from "@mui/icons-material/CheckOutlined";
import HistoryOutlined from "@mui/icons-material/HistoryOutlined";
import { Button, ListItemIcon, ListItemText, Menu, MenuItem } from "@mui/material";

export interface SubmissionVersion {
  // Undefined for the latest, the one being reviewed; otherwise the path of the copy kept as it was sent back
  id?: string;
  label: string;
  detail: string;
}

// PRETOTYPE. Which version of the submission is read, in the app bar, by its name; a choice of the others. The latest
// is outlined; an older one, read instead, is filled, to stand out as not the one being reviewed.
export default function VersionMenu({ versions, viewing, onView }: {
  // Newest first
  versions: SubmissionVersion[];
  viewing?: string;
  onView: (id: string | undefined) => void;
}) {
  const [ menuAt, setMenuAt ] = useState<HTMLElement | null>(null);
  const shown = versions.find(one => one.id === viewing) ?? versions[0];
  const older = viewing !== undefined;
  return (
    <>
      <Button
        size="small" variant={older ? "contained" : "outlined"} color="primary"
        startIcon={<HistoryOutlined />} endIcon={<ArrowDropDownOutlined />}
        aria-haspopup="menu" aria-expanded={menuAt !== null} aria-label={`${shown.label}, choose another version`}
        onClick={event => setMenuAt(event.currentTarget)}
        sx={{ py: 0, px: 1, minHeight: 24, typography: "caption", fontWeight: "fontWeightBold", whiteSpace: "nowrap",
          ...older ? { color: "primary.contrastText" } : {},
          "& .MuiButton-startIcon, & .MuiButton-endIcon": { mx: 0.25 }, "& .MuiSvgIcon-root": { fontSize: "1rem" } }}
      >
        {shown.label}
      </Button>
      <Menu anchorEl={menuAt} open={menuAt !== null} onClose={() => setMenuAt(null)}>
        {versions.map(version => (
          <MenuItem
            key={version.id ?? "latest"} selected={version.id === viewing}
            onClick={() => {
              onView(version.id);
              setMenuAt(null);
            }}
          >
            <ListItemIcon>{version.id === viewing ? <CheckOutlined fontSize="small" /> : null}</ListItemIcon>
            <ListItemText primary={version.label} secondary={version.detail} />
          </MenuItem>
        ))}
      </Menu>
    </>
  );
}
