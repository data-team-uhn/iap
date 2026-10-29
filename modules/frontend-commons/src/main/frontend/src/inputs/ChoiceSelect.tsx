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

import { MenuItem, TextField } from "@mui/material";

import { type Choice, withCurrent } from "./choices";
import { fromSingle, picker, singleValue, useFieldProps, type ValueInputProps } from "./valueInput";
import ValueText from "./ValueText";

interface ChoiceSelectProps extends ValueInputProps {
  choices: Choice[];
  // Whether the value can be cleared. By default, when it is not required.
  allowNone?: boolean;
  // Whether the list opens as soon as it appears
  openOnMount?: boolean;
}

// One value, picked from a list.
export default function ChoiceSelect(props: ChoiceSelectProps) {
  const {
    label,
    required = false,
    values,
    choices,
    readOnly = false
  } = props;
  const { allowNone = !required, openOnMount = false } = props;
  const { field, visit } = useFieldProps(props);
  const current = singleValue(values);

  if (readOnly) {
    return <ValueText label={label} values={values} choices={choices} />;
  }
  return (
    <TextField
      select
      {...field}
      value={current}
      onChange={event => picker(props)(fromSingle(event.target.value))}
      onBlur={visit}
      slotProps={{ select: { defaultOpen: openOnMount } }}
    >
      { allowNone && <MenuItem value=""><em>None</em></MenuItem> }
      { withCurrent(choices, current).map(choice => (
        <MenuItem key={choice.value} value={choice.value}>{choice.label}</MenuItem>
      )) }
    </TextField>
  );
}
