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

// Lets a person enter a calendar day.

import { type SyntheticEvent, useState } from "react";

import { TextField } from "@mui/material";

import { useCommit, useFieldProps, type ValueInputProps } from "./valueInput";
import { DATE_PROBLEM, fromSingle, singleValue } from "./values";
import ValueText from "./ValueText";

// One calendar day, stored as `yyyy-mm-dd` whatever the reader's locale displays. A day only partly entered
// holds no value, is flagged once the field is left, and is not committed.
export default function DateInput(props: ValueInputProps) {
  const { label, values, onChange, readOnly = false } = props;
  // The browser reports a partly entered day as empty, and only `badInput` tells it from an empty field
  const [ partial, setPartial ] = useState(false);
  const { field, visit } = useFieldProps(props, partial ? [ DATE_PROBLEM ] : []);
  const finishing = useCommit({ ...props, finished: !partial }, visit);

  if (readOnly) {
    return <ValueText label={label} values={values} />;
  }
  return (
    <TextField
      {...field}
      type="date"
      value={singleValue(values)}
      slotProps={{
        inputLabel: { shrink: true },
        // Each edit of a part of the day is an input event, though the value stays empty and React reports no change
        htmlInput: {
          onInput: (event: SyntheticEvent<HTMLInputElement>) => setPartial(event.currentTarget.validity.badInput),
        },
      }}
      onChange={event => onChange(fromSingle(event.target.value))}
      {...finishing}
    />
  );
}
