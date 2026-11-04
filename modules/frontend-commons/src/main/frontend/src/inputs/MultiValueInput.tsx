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

import { useMemo, useState } from "react";

import { Autocomplete, Chip, TextField } from "@mui/material";

import { type Choice, withValues } from "./choices";
import { changeAndCommit, useFieldProps, type ValueInputProps } from "./valueInput";
import ValueText from "./ValueText";

interface MultiValueInputProps extends ValueInputProps {
  choices?: Choice[];
  // How a stored value the list does not offer is labelled. By default, as it is.
  labelOf?: (value: string) => string;
  // Whether a value can be typed rather than picked. By default, only when there are no choices.
  free?: boolean;
  loading?: boolean;
  // The values one newly typed entry stands for, e.g. each item of "a, b"
  splitTyped?: (typed: string) => string[];
  // Whether a value's chip is marked in error
  chipError?: (value: string) => boolean;
  openOnMount?: boolean;
}

// Several values, shown as chips.
export default function MultiValueInput(props: MultiValueInputProps) {
  const { label, values, choices = [], labelOf, splitTyped, chipError, openOnMount = false, disabled } = props;
  const { free = choices.length === 0, loading = false, readOnly = false } = props;
  const { field, visit } = useFieldProps(props);
  const [ listed, setListed ] = useState(openOnMount);
  const known = useMemo(() => withValues(choices, values, labelOf), [ choices, values, labelOf ]);
  const labels = useMemo(() => new Map(known.map(choice => [ choice.value, choice.label ])), [ known ]);
  const labelFor = (value: string) => labels.get(value) ?? value;

  if (readOnly) {
    return <ValueText label={label} values={values} choices={known} />;
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
      options={known.map(choice => choice.value)}
      getOptionLabel={labelFor}
      getOptionKey={value => value}
      value={values}
      disabled={disabled}
      onChange={(_event, next) => changeAndCommit(props)(splitTyped
        ? next.flatMap(value => values.includes(value) ? [ value ] : splitTyped(value))
        : next)}
      onBlur={visit}
      renderValue={(selected, getItemProps) => selected.map((value, index) => {
        const { key, ...itemProps } = getItemProps({ index });
        return (
          <Chip
            key={key}
            label={labelFor(value)}
            color={chipError?.(value) ? "error" : "default"}
            // A long value wraps rather than being cut short
            sx={{ height: "auto" }}
            slotProps={{ label: { sx: { whiteSpace: "normal", py: 0.5 } } }}
            {...itemProps}
          />
        );
      })}
      renderInput={params => <TextField {...params} {...field} />}
    />
  );
}
