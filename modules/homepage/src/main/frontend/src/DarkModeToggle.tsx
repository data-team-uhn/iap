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

// A button that switches the UI between the light and dark colour schemes. MUI keeps the choice
// in the browser for later visits.
function DarkModeToggle() {
  const { mode, systemMode, setMode } = useColorScheme();

  // MUI gives no mode until it has set up the colour scheme, and the button shows nothing until
  // then
  if (!mode) {
    return null;
  }

  const resolvedMode = (mode === "system" ? systemMode : mode) ?? "light";
  const otherMode = resolvedMode === "dark" ? "light" : "dark";

  return (
    <Tooltip title={`Switch to ${otherMode} mode`}>
      <IconButton
        aria-label={`Switch to ${otherMode} mode`}
        onClick={() => setMode(otherMode)}
        size="small"
      >
        {resolvedMode === "dark" ? <LightModeIcon /> : <DarkModeIcon />}
      </IconButton>
    </Tooltip>
  );
}

export default DarkModeToggle;
