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

import type { MouseEvent } from "react";

import Checkbox from "@mui/material/Checkbox";


import type { CheckState } from "../selectionContext";

interface TriStateCheckboxProps {
  state: CheckState;
  /** Called with `true` to select everything below, or `false` to deselect it. */
  onChange: (select: boolean) => void;
  "aria-label": string;
}

/**
 * The checkbox for a whole database or collection, showing all, some or none of it chosen. Pressing
 * it while some are chosen selects all, so no choice is lost.
 */
export default function TriStateCheckbox({ state, onChange, "aria-label": ariaLabel }:
TriStateCheckboxProps) {
  return (
    <Checkbox
      checked={state === "all"}
      indeterminate={state === "some"}
      onClick={(event: MouseEvent<HTMLButtonElement>) => {
        // The row behind this is a button of its own, and clicking the box is not clicking the row
        event.stopPropagation();
        onChange(state !== "all");
      }}
      slotProps={{ input: { "aria-label": ariaLabel } }}
      sx={{ flex: "none" }}
    />
  );
}
