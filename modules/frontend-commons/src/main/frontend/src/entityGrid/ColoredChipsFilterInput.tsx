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

// What a picked value gives a chip to show. A column may declare its choices either as plain strings
// or as objects, and a chip expects a proper object.
function chipOption(option: unknown): { label: string; color?: string; variant?: string } {
  if (typeof option !== "object" || option === null) {
    return { label: String(option) };
  }
  // Partial, not ColoredValueOption: the point of this function is that the option may not be
  // one, so asserting every field is present would make the fallbacks below look pointless
  const { label, value, color, variant } = option as Partial<ColoredValueOption>;
  return { label: label ?? String(value), color, variant };
}

// The stock "is any of" input for choice columns, with each picked value rendered as a chip
// styled from its option's own color and variant (when it declares a usable color; see
// chipStyle) instead of the stock outlined look.
function ColoredChipsFilterInput(props: GridFilterInputMultipleSingleSelectProps) {
  const theme = useTheme();
  // Every level of slotProps is merged rather than set: passing an object here replaces whatever
  // the filter form passed for the same slot, and what it passes is the sizing that keeps this
  // input the same height as the column and operator selects beside it.
  const { slotProps, ...rest } = props;
  const rootProps = slotProps?.root;
  return (
    <GridFilterInputMultipleSingleSelect
      {...rest}
      slotProps={{
        ...slotProps,
        root: {
          ...rootProps,
          slotProps: {
            ...rootProps?.slotProps,
            // Restated because this replaces the stock input's own slotProps: the text field is
            // the focus target for a newly added condition
            textField: { type: "text", inputRef: props.focusElementRef },
            chip: option => {
              const { label, color, variant } = chipOption(option);
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
