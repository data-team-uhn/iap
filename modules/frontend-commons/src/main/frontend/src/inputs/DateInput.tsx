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

import { TextField } from "@mui/material";

import { commitProps, fromSingle, singleValue, useFieldProps, type ValueInputProps } from "./valueInput";
import ValueText from "./ValueText";

// One calendar day, stored as `yyyy-mm-dd` whatever the reader's locale displays.
export default function DateInput(props: ValueInputProps) {
  const { label, values, onChange, onCommit, readOnly = false } = props;
  const { field, visit } = useFieldProps(props);

  if (readOnly) {
    return <ValueText label={label} values={values} />;
  }
  return (
    <TextField
      {...field}
      type="date"
      value={singleValue(values)}
      slotProps={{ inputLabel: { shrink: true } }}
      onChange={event => onChange(fromSingle(event.target.value))}
      {...commitProps(input => {
        visit();
        // A half-typed day reads as empty, which would clear the stored one. badInput tells the two apart.
        if (!(input as HTMLInputElement).validity.badInput) {
          onCommit?.(values);
        }
      })}
    />
  );
}
