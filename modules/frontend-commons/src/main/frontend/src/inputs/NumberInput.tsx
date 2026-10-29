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

import { isDecimalSoFar, isWholeSoFar } from "../numberText";
import { commitProps, fromSingle, singleValue, useFieldProps, type ValueInputProps } from "./valueInput";
import ValueText from "./ValueText";

const SO_FAR = { whole: isWholeSoFar, decimal: isDecimalSoFar };
const INPUT_MODES = { whole: "numeric", decimal: "decimal" } as const;

interface NumberInputProps extends ValueInputProps {
  // - whole: a sign and digits, e.g. "-12"
  // - decimal: also a point and an exponent, e.g. "2.5e-3"
  kind: "whole" | "decimal";
}

// One number, typed. A keystroke that would stop the value being a number is refused.
export default function NumberInput(props: NumberInputProps) {
  const { label, values, onChange, onCommit, kind, readOnly = false } = props;
  const { field, visit } = useFieldProps(props);

  if (readOnly) {
    return <ValueText label={label} values={values} />;
  }
  const single = singleValue(values);
  const soFar = SO_FAR[kind];
  // A number field would show a stored value that is not a number as blank.
  // A text field shows it as written, and it can be typed over freely until it is one.
  const numeric = soFar(single);
  return (
    <TextField
      {...field}
      value={single}
      slotProps={{ htmlInput: { inputMode: INPUT_MODES[kind] } }}
      onChange={event => {
        const typed = event.target.value;
        if (soFar(typed) || !numeric) {
          onChange(fromSingle(typed));
        }
      }}
      {...commitProps(() => {
        visit();
        onCommit?.(values);
      })}
    />
  );
}
