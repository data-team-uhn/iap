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

import { baseFieldProps, noneItem, ParameterText, picker } from "./parameterWidget";
import TextParameterField from "./TextParameterField";
import { isMultiple } from "./workflowTypes";

import type { ParameterFieldProps } from "./parameterWidget";

// What the server would store: true or false in any case, as a diagram written by hand or by another
// tool may spell it. It drops any other word, and the node type's default applies.
function stored(value: string): "true" | "false" | undefined {
  const word = value.trim().toLowerCase();
  return word === "true" || word === "false" ? word : undefined;
}

const shown = (value: string): string => {
  const word = stored(value);
  return word === undefined ? value : word === "true" ? "Yes" : "No";
};

// A parameter holding yes or no. A list of them is typed the way text is.
export default function BooleanParameterField(props: ParameterFieldProps) {
  const { parameter, values, readOnly = false } = props;
  const single = values.at(0) ?? "";

  // Read as a word rather than as a tick nobody can move
  if (readOnly) {
    return <ParameterText parameter={parameter} shown={values.map(shown)} />;
  }

  if (isMultiple(parameter)) {
    return <TextParameterField {...props} />;
  }

  const pick = picker(props);
  const base = baseFieldProps(parameter);
  // Shown as unset and flagged, so the author picks one rather than reading a value the server ignores
  const ignored = single !== "" && stored(single) === undefined;

  // A choice of three rather than a tick: an optional yes-or-no left unset keeps the node type's own
  // default, which neither ticked nor unticked can say.
  return (
    <TextField
      select
      {...base}
      value={stored(single) ?? ""}
      error={ignored}
      helperText={ignored ? `"${single}" is not true or false, so it will be ignored` : base.helperText}
      onChange={event => pick(event.target.value === "" ? [] : [ event.target.value ])}
    >
      { !base.required && noneItem() }
      <MenuItem value="true">Yes</MenuItem>
      <MenuItem value="false">No</MenuItem>
    </TextField>
  );
}
