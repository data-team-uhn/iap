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

import { isBoolean } from "./parameterValues";
import { inputProps } from "./parameterWidget";
import TextParameterField from "./TextParameterField";
import { isMultiple } from "./workflowTypes";

import type { ParameterFieldProps } from "./parameterWidget";

// What the server would store, as the word the field picks. A diagram written by hand or by another
// tool may spell it in any case. Any other word is dropped, and the node type's default applies.
function stored(value: string): string | undefined {
  const word = value.trim().toLowerCase();
  return isBoolean(word) ? word : undefined;
}

// A parameter holding yes or no. A list of them is typed the way text is.
export default function BooleanParameterField(props: ParameterFieldProps) {
  const { parameter, values, readOnly = false } = props;

  if (isMultiple(parameter) && !readOnly) {
    return <TextParameterField {...props} />;
  }
  // A word the server ignores is shown as unset and flagged, so the author picks one rather than
  // reading a value that is not there. A read-only field shows it as written.
  const words = values.flatMap(value => stored(value) ?? []);
  return (
    <BooleanInput
      {...inputProps(props)}
      values={readOnly ? values.map(value => stored(value) ?? value) : words}
    />
  );
}
