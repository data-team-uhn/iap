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

import { fromSingle, isWholeSoFar, singleValue } from "./parameterValues";
import { baseFieldProps, commitProps, useVisited, valueProblems } from "./parameterWidget";
import TextParameterField from "./TextParameterField";
import { isMultiple } from "./workflowTypes";

import type { ParameterFieldProps } from "./parameterWidget";

// A parameter holding a whole number. A list, a choice among offered values and a read-only value are
// shown the way text is.
export default function LongParameterField(props: ParameterFieldProps) {
  const { parameter, values, onChange, onCommit, readOnly = false } = props;
  const [ visited, visit ] = useVisited();

  if (readOnly || isMultiple(parameter) || parameter.options.length > 0) {
    return <TextParameterField {...props} />;
  }

  const single = singleValue(values);
  // A text field rather than a number one, which would show a value that is no number as blank. One
  // the diagram holds is shown as written and flagged, and can be typed over freely until it is a number.
  const numeric = isWholeSoFar(single);
  return (
    <TextField
      {...baseFieldProps(parameter, valueProblems(parameter, values, visited))}
      value={single}
      slotProps={{ htmlInput: { inputMode: "numeric" } }}
      onChange={event => {
        const typed = event.target.value;
        if (isWholeSoFar(typed) || !numeric) {
          onChange(fromSingle(typed));
        }
      }}
      {...commitProps(() => {
        visit();
        onCommit(values);
      })}
    />
  );
}
