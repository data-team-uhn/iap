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

// A choice a column may offer for filtering. A value picked in "is any of" shows as a chip styled
// from the choice's color and variant, as chipStyle reads them; a stock chip without a color.
export interface ColoredValueOption {
  value: string;
  label: string;
  // A CSS color, such as "#1d6a3a"
  color?: string;
  // A chipStyle variant, such as "filled"
  variant?: string;
}

// What a chip shows for a picked choice. A column may declare its choices as plain strings or as
// objects, and the chip shows a string as its own label.
function chipOption(option: unknown): { label: string; color?: string; variant?: string } {
  if (typeof option !== "object" || option === null) {
    return { label: String(option) };
  }
  // Partial, since the object may lack any of the fields
  const { label, value, color, variant } = option as Partial<ColoredValueOption>;
  return { label: label ?? String(value), color, variant };
}

// The stock "is any of" input of a choice column, with each picked value as a chip styled from its
// choice's color and variant. A choice without a usable color keeps the stock chip.
function ColoredChipsFilterInput(props: GridFilterInputMultipleSingleSelectProps) {
  const theme = useTheme();
  // Every level of slotProps is merged, not replaced: the filter form passes the sizing that keeps
  // this input as tall as the column and operator selects beside it.
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
            // The stock input's own text field props, which this replaces: a new condition focuses
            // the text field
            textField: { type: "text", inputRef: props.focusElementRef },
            chip: option => {
              const { label, color, variant } = chipOption(option);
              const style = chipStyle(theme, color, variant);
              return {
                label,
                // A filled chip, which the style paints over entirely
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
