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

import { useTheme } from "@mui/material/styles";
import useMediaQuery from "@mui/material/useMediaQuery";

/**
 * Whether there is room for the selection beside the tree rather than under it.
 *
 * One place decides it, because two would eventually disagree: the panel picks between a side panel
 * and a sheet, and what goes inside picks its own layout to match. A sheet holding the wide layout
 * is how the list of chosen fields ends up with no room at all.
 */
export function useIsCompact(): boolean {
  const theme = useTheme();
  return useMediaQuery(theme.breakpoints.down("md"));
}
