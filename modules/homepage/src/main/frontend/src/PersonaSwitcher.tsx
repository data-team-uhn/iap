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

import CheckIcon from "@mui/icons-material/Check";
import {
  Divider,
  ListItemIcon,
  ListItemText,
  MenuItem,
  Typography,
} from "@mui/material";

import {
  availablePersonas,
  personaLabel,
  setActivePersona,
  usePersona,
} from "@iap/ui-extension/personas";

// A section of a menu that lists the personas the user may act as, with the active one checked.
// `onChoose` is called once the user has chosen one. Nothing is rendered when there is only one
// persona to act as.
function PersonaSwitcher({ onChoose }: { onChoose?: () => void }) {
  const active = usePersona();
  const personas = availablePersonas();

  if (personas.length < 2) {
    return null;
  }

  const choose = (persona: string) => {
    setActivePersona(persona);
    onChoose?.();
  };

  return (
    <>
      <Typography variant="description" sx={{ display: "block", px: 2, py: 0.5 }}>
        Acting as
      </Typography>
      {
        personas.map(persona => (
          <MenuItem
            key={persona}
            role="menuitemradio"
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
      <Divider />
    </>
  );
}

export default PersonaSwitcher;
