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

import BooleanInput from "@iap/frontend-commons/inputs/BooleanInput";

import { inputProps } from "./parameterWidget";
import TextParameterField from "./TextParameterField";
import { isMultiple } from "./workflowTypes";

import type { ParameterFieldProps } from "./parameterWidget";

// What the server would store: true or false in any case, as a diagram written by hand or by another
// tool may spell it. It drops any other word, and the node type's default applies.
function stored(value: string): "true" | "false" | undefined {
  const word = value.trim().toLowerCase();
  return word === "true" || word === "false" ? word : undefined;
}

// A parameter holding yes or no. A list of them is typed the way text is.
export default function BooleanParameterField(props: ParameterFieldProps) {
  const { parameter, values, readOnly = false } = props;

  if (isMultiple(parameter) && !readOnly) {
    return <TextParameterField {...props} />;
  }
  // Read as a word rather than as a tick nobody can move, and as written when it is no word the server
  // reads
  if (readOnly) {
    return <BooleanInput {...inputProps(props)} values={values.map(value => stored(value) ?? value)} />;
  }
  // Shown as unset and flagged, so the author picks one rather than reading a value the server ignores
  const single = values.at(0) ?? "";
  const word = stored(single);
  const problems = single !== "" && word === undefined
    ? [ `"${single}" is not true or false, so it will be ignored` ] : [];
  return <BooleanInput {...inputProps(props, problems)} values={word === undefined ? [] : [ word ]} />;
}
