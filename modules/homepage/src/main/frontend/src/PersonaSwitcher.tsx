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

import CheckIcon from "@mui/icons-material/Check";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import {
  Button,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
} from "@mui/material";

import {
  availablePersonas,
  personaLabel,
  setActivePersona,
  usePersona,
} from "@iap/ui-extension/personas";

// The button and the menu it opens reference each other by id, so that assistive technology can tell
// they are one control. Constants rather than useId(): there is exactly one persona switcher per page.
const TRIGGER_ID = "persona-switcher-button";
const MENU_ID = "persona-switcher-menu";

// The persona the user is currently acting as, and a menu to change it: "put on the reviewer hat".
//
// The active persona is shown as a label rather than hidden behind an icon, because the rest of the
// UI changes with it. It also resets to the least permissive persona whenever the page loads, so
// someone who chose Reviewer earlier needs to be able to see, without opening anything, that they
// are back to Submitter.
//
// Switching personas only changes what is displayed; it grants nothing. Registered on the
// `iap/appBar/entry` extension point, end section, ahead of the user menu.
function PersonaSwitcher() {
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  const active = usePersona();
  const personas = availablePersonas();
  const open = Boolean(anchor);

  const choose = (persona: string) => {
    setActivePersona(persona);
    setAnchor(null);
  };

  // With nothing to switch between, the control would be a label that does nothing.
  if (personas.length < 2) {
    return null;
  }

  return (
    <>
      <Button
        id={TRIGGER_ID}
        color="inherit"
        size="small"
        aria-label={`Acting as ${personaLabel(active)}. Change persona`}
        aria-haspopup="menu"
        aria-controls={open ? MENU_ID : undefined}
        aria-expanded={open}
        onClick={event => setAnchor(event.currentTarget)}
        endIcon={<ExpandMoreIcon />}
        sx={{ textTransform: "none" }}
      >
        {personaLabel(active)}
      </Button>
      <Menu
        id={MENU_ID}
        anchorEl={anchor}
        open={open}
        onClose={() => setAnchor(null)}
        slotProps={{ list: { "aria-labelledby": TRIGGER_ID } }}
      >
        {
          personas.map(persona => (
            <MenuItem
              key={persona}
              // A menu that picks exactly one of a set is a radio group, not a list of commands: the
              // check mark is decorative, so `menuitemradio` + aria-checked is the only thing that
              // tells a screen reader which hat is currently on. `selected` remains for the styling.
              role="menuitemradio"
              aria-checked={persona === active}
              selected={persona === active}
              onClick={() => choose(persona)}
            >
              <ListItemIcon>
                { persona === active && <CheckIcon fontSize="small" /> }
              </ListItemIcon>
              <ListItemText>{personaLabel(persona)}</ListItemText>
            </MenuItem>
          ))
        }
      </Menu>
    </>
  );
}

export default PersonaSwitcher;
