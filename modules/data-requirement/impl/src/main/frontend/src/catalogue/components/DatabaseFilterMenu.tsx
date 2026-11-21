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

/** Chooses which databases to browse. The choice is kept as the set of databases left out. */
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
          <MenuItem
            key={database.identifier}
            // Tells assistive technology whether the database is included; the checkbox below only draws it
            role="menuitemcheckbox"
            selected={!excluded.has(database.identifier)}
            onClick={() => { toggle(database.identifier); }}
          >
            <Checkbox
              checked={!excluded.has(database.identifier)}
              // The menu item is the control; the checkbox only draws its state
              tabIndex={-1}
              slotProps={{ input: { "aria-hidden": true } }}
              disableRipple
              size="small"
            />
            <ListItemText primary={database.label} secondary={database.description || undefined} />
          </MenuItem>
        ))}
        <Divider />
        {/* These two close the menu; ticking one database leaves it open */}
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
