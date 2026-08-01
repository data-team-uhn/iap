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
import {
  GridFilterInputMultipleSingleSelect,
  type GridFilterInputMultipleSingleSelectProps,
} from "@mui/x-data-grid-pro";

import { safeCssColor } from "../safeColor";

// A choice a column may offer for filtering. The optional color carries over into the filter
// panel: values picked in "is any of" show as chips in their option's color, matching how the
// grid's own cells present them (e.g. tag definition colors).
export interface ColoredValueOption {
  value: string;
  label: string;
  color?: string;
}

// The stock "is any of" input for choice columns, with each picked value rendered as a chip
// in its option's own color (when it declares a safe one; see safeCssColor) instead of the
// stock outlined look.
function ColoredChipsFilterInput(props: GridFilterInputMultipleSingleSelectProps) {
  const theme = useTheme();
  return (
    <GridFilterInputMultipleSingleSelect
      {...props}
      slotProps={{
        root: {
          slotProps: {
            // This replaces the stock input's own slotProps, so its text field wiring (the
            // focus target for the newly added condition) is restated here
            textField: { type: "text", inputRef: props.focusElementRef },
            // The chips only ever render declared options: the stock input resolves the
            // filter's values back through the column's options before rendering, so even a
            // freely typed value never reaches here as a bare string
            chip: option => {
              const { label, color } = option as ColoredValueOption;
              const safeColor = safeCssColor(color);
              return {
                label,
                ...safeColor && {
                  variant: "filled" as const,
                  style: { backgroundColor: safeColor, color: theme.palette.getContrastText(safeColor) },
                },
              };
            },
          },
        },
      }}
    />
  );
}

export default ColoredChipsFilterInput;
