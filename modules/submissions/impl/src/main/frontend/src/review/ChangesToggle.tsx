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
import CheckBoxOutlineBlankOutlined from "@mui/icons-material/CheckBoxOutlineBlankOutlined";
import CheckBoxOutlined from "@mui/icons-material/CheckBoxOutlined";
import DifferenceOutlined from "@mui/icons-material/DifferenceOutlined";
import { Button, ButtonGroup, ListItemIcon, ListItemText, Menu, MenuItem } from "@mui/material";

// PRETOTYPE. Whether the review screen shows what changed since the submission was last sent back, on every part:
// documents compared with their version then, answers with what they were. A menu beside it says whether minor
// changes, of punctuation or letter case, count.
export default function ChangesToggle({ since, on, onChange, minor, onMinorChange }: {
  // When it was last sent back, as said on the button
  since: string;
  on: boolean;
  onChange: (on: boolean) => void;
  minor: boolean;
  onMinorChange: (minor: boolean) => void;
}) {
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  return (
    <>
      {/* Tinted while on, as the notes' filters are, rather than filled, being on most of the time */}
      <ButtonGroup size="small" variant="outlined"
        sx={theme => ({ flexShrink: 0, bgcolor: "background.paper", "& .MuiButton-root": { whiteSpace: "nowrap",
          ...on ? { color: "primary.main", borderColor: "primary.main",
            bgcolor: theme.alpha((theme.vars ?? theme).palette.primary.main, 0.08) }
            : { color: "text.secondary", borderColor: "divider" } },
        "& .MuiButtonGroup-grouped:not(:last-of-type)": { borderRightColor: on ? "primary.main" : "divider" } })}>
        <Button aria-pressed={on} startIcon={<DifferenceOutlined />} onClick={() => onChange(!on)}>
          Changes since {since}
        </Button>
        <Button aria-label="Which changes to show" aria-haspopup="menu" sx={{ px: 0.5, minWidth: 0 }}
          onClick={event => setAnchor(event.currentTarget)}>
          <ArrowDropDownOutlined fontSize="small" />
        </Button>
      </ButtonGroup>
      <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}>
        <MenuItem role="menuitemcheckbox" aria-checked={minor} onClick={() => onMinorChange(!minor)}>
          <ListItemIcon>{minor ? <CheckBoxOutlined /> : <CheckBoxOutlineBlankOutlined />}</ListItemIcon>
          <ListItemText primary="Include minor changes" secondary="Of punctuation or letter case alone" />
        </MenuItem>
      </Menu>
    </>
  );
}
