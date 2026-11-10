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

import type { ReactNode } from "react";

import { InputAdornment } from "@mui/material";

// What sits in the top right corner of an answer's input box, or nothing. Kept at the top, so a box
// that grows with its text keeps it in the corner rather than halfway down.
export function getCornerAdornment(corner: ReactNode): ReactNode {
  if (!corner) {
    return undefined;
  }
  return (
    <InputAdornment position="end" sx={{ alignSelf: "flex-start", mt: -2, mr: -1.5 }}>
      {corner}
    </InputAdornment>
  );
}
