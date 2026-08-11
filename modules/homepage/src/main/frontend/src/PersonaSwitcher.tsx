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
        color="inherit"
        size="small"
        aria-label={`Acting as ${personaLabel(active)}. Change persona`}
        aria-haspopup="menu"
        onClick={event => setAnchor(event.currentTarget)}
        endIcon={<ExpandMoreIcon />}
        sx={{ textTransform: "none" }}
      >
        {personaLabel(active)}
      </Button>
      <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}>
        {
          personas.map(persona => (
            <MenuItem
              key={persona}
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
