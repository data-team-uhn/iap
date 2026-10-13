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

import { baseFieldProps, commitProps } from "./parameterWidget";
import TextParameterField from "./TextParameterField";
import { isMultiple } from "./workflowTypes";

import type { ParameterFieldProps } from "./parameterWidget";

// A whole number, or nothing yet: what the server stores a `long` as, and so all a field of one takes.
const WHOLE = /^-?\d*$/;

// A parameter holding a whole number. A list, a choice among offered values and a read-only value are
// shown the way text is.
export default function LongParameterField(props: ParameterFieldProps) {
  const { parameter, values, onChange, onCommit, readOnly = false } = props;

  if (readOnly || isMultiple(parameter) || parameter.options.length > 0) {
    return <TextParameterField {...props} />;
  }

  return (
    <TextField
      type="number"
      {...baseFieldProps(parameter)}
      value={values.at(0) ?? ""}
      // A whole number is what the server accepts, so the browser is the one to refuse a decimal point
      slotProps={{ htmlInput: { step: 1, inputMode: "numeric" } }}
      onChange={event => {
        const typed = event.target.value;
        if (WHOLE.test(typed)) {
          onChange(typed === "" ? [] : [ typed ]);
        }
      }}
      {...commitProps(() => onCommit(values))}
    />
  );
}
