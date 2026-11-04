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

import {
  isDecimalNumber, isDecimalSoFar, isWholeNumber, isWholeSoFar, NUMBER_PROBLEM, WHOLE_NUMBER_PROBLEM,
} from "../numberText";
import { commitProps, fromSingle, singleValue, useFieldProps, type ValueInputProps } from "./valueInput";
import ValueText from "./ValueText";

const KINDS = {
  whole: { complete: isWholeNumber, soFar: isWholeSoFar, problem: WHOLE_NUMBER_PROBLEM, inputMode: "numeric" },
  decimal: { complete: isDecimalNumber, soFar: isDecimalSoFar, problem: NUMBER_PROBLEM, inputMode: "decimal" },
} as const;

interface NumberInputProps extends ValueInputProps {
  // - whole: a sign and digits, e.g. "-12"
  // - decimal: also a point and an exponent, e.g. "2.5e-3"
  kind: "whole" | "decimal";
}

// One number, typed. Text that cannot become one is flagged at once, and a number left unfinished once the field is
// left. Only a finished number, or nothing, is committed.
export default function NumberInput(props: NumberInputProps) {
  const { label, values, onChange, kind, problems = [], readOnly = false } = props;
  const { complete, soFar, problem, inputMode } = KINDS[kind];
  const typed = singleValue(values).trim();
  const finished = typed === "" || complete(typed);
  const { field, commit } = useFieldProps(
    { ...props, problems: soFar(typed) ? problems : [ ...problems, problem ] },
    finished ? [] : [ problem ]);

  if (readOnly) {
    return <ValueText label={label} values={values} />;
  }
  // A text field rather than a number field, which would show a stored value that is no number as blank
  return (
    <TextField
      {...field}
      value={singleValue(values)}
      slotProps={{ htmlInput: { inputMode } }}
      onChange={event => onChange(fromSingle(event.target.value))}
      {...commitProps(() => commit(finished))}
    />
  );
}
