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

import { useState } from "react";

import { Autocomplete, Chip, TextField } from "@mui/material";

import { type Choice, choiceLabel } from "./choices";
import { picker, useFieldProps, type ValueInputProps } from "./valueInput";
import ValueText from "./ValueText";

interface MultiValueInputProps extends ValueInputProps {
  choices?: Choice[];
  // Whether a value can be typed rather than picked. Defaults to true if are no choices, false otherwise.
  free?: boolean;
  loading?: boolean;
  // The values one typed entry stands for, e.g. each item of "a, b"
  splitTyped?: (typed: string) => string[];
  // Whether a value's chip is marked in error
  chipError?: (value: string) => boolean;
  openOnMount?: boolean;
}

// Several values, shown as chips.
export default function MultiValueInput(props: MultiValueInputProps) {
  const {
    label,
    values,
    choices = [],
    splitTyped,
    chipError,
    openOnMount = false,
    disabled,
    readOnly = false
  } = props;
  const { free = choices.length === 0, loading = false } = props;
  const { field, visit } = useFieldProps(props);
  const [ listed, setListed ] = useState(openOnMount);

  if (readOnly) {
    return <ValueText label={label} values={values} choices={choices} />;
  }
  return (
    <Autocomplete<string, true, false, boolean>
      multiple
      open={listed}
      onOpen={() => setListed(true)}
      onClose={() => setListed(false)}
      freeSolo={free}
      loading={loading}
      autoSelect={free}
      options={choices.map(choice => choice.value)}
      getOptionLabel={value => choiceLabel(choices, value)}
      value={values}
      disabled={disabled}
      onChange={(_event, next) => picker(props)(splitTyped ? next.flatMap(splitTyped) : next)}
      onBlur={visit}
      renderValue={(selected, getItemProps) => selected.map((value, index) => {
        const { key, ...itemProps } = getItemProps({ index });
        return (
          <Chip
            key={key}
            label={choiceLabel(choices, value)}
            color={chipError?.(value) ? "error" : "default"}
            // A long value wraps rather than being cut short
            sx={{ height: "auto" }}
            slotProps={{ label: { sx: { whiteSpace: "normal", py: 0.5 } } }}
            {...itemProps}
          />
        );
      })}
      renderInput={params => (
        <TextField
          {...params}
          label={field.label}
          required={field.required}
          error={field.error}
          helperText={field.helperText}
        />
      )}
    />
  );
}
