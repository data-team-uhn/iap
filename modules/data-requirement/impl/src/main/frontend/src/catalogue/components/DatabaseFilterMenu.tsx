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

import ArrowDropDownIcon from "@mui/icons-material/ArrowDropDown";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Divider from "@mui/material/Divider";
import ListItemText from "@mui/material/ListItemText";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";

import type { CatalogueDatabase } from "../types";

interface DatabaseFilterMenuProps {
  databases: CatalogueDatabase[];
  excluded: ReadonlySet<string>;
  onChange: (excluded: ReadonlySet<string>) => void;
}

/**
 * Which source systems to browse.
 *
 * Phrased as exclusions rather than inclusions so that a catalogue gaining a database shows it
 * without anybody opting in — the opposite would quietly hide new data from everyone who had ever
 * touched this menu.
 */
export default function DatabaseFilterMenu({ databases, excluded, onChange }: DatabaseFilterMenuProps) {
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  const includedCount = databases.length - excluded.size;

  const toggle = (identifier: string) => {
    const next = new Set(excluded);
    if (next.has(identifier)) {
      next.delete(identifier);
    } else {
      next.add(identifier);
    }
    onChange(next);
  };

  return (
    <Box sx={{ flex: "none" }}>
      <Button
        size="small"
        onClick={event => { setAnchor(event.currentTarget); }}
        endIcon={<ArrowDropDownIcon />}
        aria-haspopup="menu"
      >
        {`Databases (${String(includedCount)} of ${String(databases.length)})`}
      </Button>
      <Menu anchorEl={anchor} open={anchor !== null} onClose={() => { setAnchor(null); }}>
        {databases.map(database => (
          <MenuItem key={database.identifier} onClick={() => { toggle(database.identifier); }}>
            <Checkbox
              checked={!excluded.has(database.identifier)}
              // The row is the control; the box is what it looks like
              tabIndex={-1}
              disableRipple
              size="small"
            />
            <ListItemText primary={database.label} secondary={database.description || undefined} />
          </MenuItem>
        ))}
        <Divider />
        {/* Terminal, unlike ticking one box: there is nothing left to say afterwards, so the menu
            gets out of the way instead of waiting to be dismissed */}
        <MenuItem onClick={() => { onChange(new Set()); setAnchor(null); }}>Include all</MenuItem>
        <MenuItem
          onClick={() => { onChange(new Set(databases.map(each => each.identifier))); setAnchor(null); }}
        >
          Exclude all
        </MenuItem>
      </Menu>
    </Box>
  );
}
