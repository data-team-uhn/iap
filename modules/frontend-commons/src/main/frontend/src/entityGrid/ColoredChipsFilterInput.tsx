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

import { chipStyle } from "../chipStyle";

// A choice a column may offer for filtering. The optional color and variant carry over into
// the filter panel: values picked in "is any of" show as chips styled like the grid's own
// cells present them (e.g. tag definition colors) — everything derived from the one color,
// per the variant (see chipStyle). Icons deliberately do not carry over: these chips sit
// inside a dense input, where the label and color say enough.
export interface ColoredValueOption {
  value: string;
  label: string;
  color?: string;
  variant?: string;
}

// The stock "is any of" input for choice columns, with each picked value rendered as a chip
// styled from its option's own color and variant (when it declares a usable color; see
// chipStyle) instead of the stock outlined look.
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
              const { label, color, variant } = option as ColoredValueOption;
              const style = chipStyle(theme, color, variant);
              return {
                label,
                // MUI's chip variant: a neutral filled base that our style fully repaints
                ...style && { variant: "filled" as const, style },
              };
            },
          },
        },
      }}
    />
  );
}

export default ColoredChipsFilterInput;
