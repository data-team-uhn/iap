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

import { Autocomplete, MenuItem, TextField } from "@mui/material";

import { choicesOf, type OperandShape, type ValueType, valueProblem, withCurrent } from "./conditionModel";

const INPUT_MODES: Partial<Record<ValueType, "numeric" | "decimal">> = {
  long: "numeric",
  double: "decimal",
  decimal: "decimal",
};

interface OperandValueInputProps {
  label: string;
  // What the values are compared with holds, which decides what they can be
  shape: OperandShape;
  several: boolean;
  value: string[];
  disabled: boolean;
  onChange: (value: string[]) => void;
}

// The values an operand is compared with: picked among the choices when there are some, or else entered as the
// type compared calls for, one or several
function OperandValueInput({ label, shape, several, value, disabled, onChange }: OperandValueInputProps) {
  const choices = choicesOf(shape);
  const problem = value.map(item => valueProblem(item, shape.type)).find(found => found !== undefined);
  const common = { label, disabled, error: problem !== undefined, helperText: problem, fullWidth: true };

  if (several) {
    const labelOf = (option: string) => choices.find(choice => choice.value === option)?.label ?? option;
    return (
      <Autocomplete<string, true, false, boolean>
        multiple
        freeSolo={choices.length === 0}
        autoSelect={choices.length === 0}
        options={choices.map(choice => choice.value)}
        getOptionLabel={labelOf}
        value={value}
        disabled={disabled}
        onChange={(_event, picked) => onChange(picked)}
        renderInput={params => <TextField {...params} {...common} />}
      />
    );
  }
  const current = value.at(0) ?? "";
  if (choices.length > 0) {
    const offered = withCurrent(choices, current);
    return (
      <TextField select {...common} value={current} onChange={event => onChange([ event.target.value ])}>
        { offered.map(choice => <MenuItem key={choice.value} value={choice.value}>{choice.label}</MenuItem>) }
      </TextField>
    );
  }
  return (
    <TextField
      {...common}
      type={shape.type === "date" ? "date" : "text"}
      value={current}
      slotProps={{
        htmlInput: { inputMode: INPUT_MODES[shape.type ?? "text"] ?? "text" },
        inputLabel: shape.type === "date" ? { shrink: true } : undefined,
      }}
      onChange={event => onChange(event.target.value === "" ? [] : [ event.target.value ])}
    />
  );
}

export default OperandValueInput;
