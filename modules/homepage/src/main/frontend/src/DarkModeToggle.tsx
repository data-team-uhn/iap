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

import DarkModeIcon from "@mui/icons-material/DarkMode";
import LightModeIcon from "@mui/icons-material/LightMode";
import { IconButton, Tooltip } from "@mui/material";
import { useColorScheme } from "@mui/material/styles";

import { useMessage } from "@iap/frontend-commons/messages";

// A small control that switches the UI between the light and dark colour schemes, registered on
// the `iap/appBar/entry` extension point (end section) so it is always reachable. The scheme
// initially follows the system preference (see the ThemeProvider `defaultMode="system"` at the
// entry point); once toggled, the explicit choice is persisted by MUI on subsequent visits.
function DarkModeToggle() {
  const message = useMessage();
  const { mode, systemMode, setMode } = useColorScheme();

  // Until the scheme system has initialized, neither the icon nor the effect of a click is
  // knowable, so render nothing for that first instant.
  if (!mode) {
    return null;
  }

  const resolvedMode = (mode === "system" ? systemMode : mode) ?? "light";
  const otherMode = resolvedMode === "dark" ? "light" : "dark";
  // One message per direction rather than one with the mode's name spliced into it: a language that
  // inflects "dark" and "light" with the noun cannot build the phrase from parts, and there is no
  // message formatter in the browser to do it properly.
  // Each key written out at its own call rather than chosen inside one. Passing the choice as an
  // argument hides both keys from the build's key check, which then reports them as defined and never
  // used — it said so about exactly this code.
  const label = otherMode === "dark"
    ? message("iap.shell.colourScheme.switchToDark")
    : message("iap.shell.colourScheme.switchToLight");

  return (
    <Tooltip title={label}>
      <IconButton
        aria-label={label}
        onClick={() => setMode(otherMode)}
        size="small"
      >
        {resolvedMode === "dark" ? <LightModeIcon /> : <DarkModeIcon />}
      </IconButton>
    </Tooltip>
  );
}

export default DarkModeToggle;
