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

import ChoiceSelect from "@iap/frontend-commons/inputs/ChoiceSelect";
import MultiValueInput from "@iap/frontend-commons/inputs/MultiValueInput";
import TextInput from "@iap/frontend-commons/inputs/TextInput";

import { refusal, splitList } from "./parameterValues";
import { inputProps } from "./parameterWidget";
import { isMultiple } from "./workflowTypes";

import type { ParameterFieldProps } from "./parameterWidget";
import type { Parameter } from "./workflowTypes";

// What is wrong with how many values a list holds, as the server would report it. An empty
// list of a required parameter is reported as missing a value instead.
function countProblems(parameter: Parameter, values: string[]): string[] {
  if (values.length > 0 && values.length < parameter.minValues) {
    return [ `At least ${parameter.minValues} values are needed` ];
  }
  if (parameter.maxValues > 0 && values.length > parameter.maxValues) {
    return [ `At most ${parameter.maxValues} values are accepted` ];
  }
  return [];
}

// A parameter edited as text: typed freely, or picked from the values its entry offers. This is also
// the field for any type that has none of its own.
export default function TextParameterField(props: ParameterFieldProps) {
  const { parameter, values, commaSeparated = false } = props;
  const choices = parameter.options;

  // Several values are always picked or typed as chips, since a list has no natural single control.
  // They are free-form when the parameter constrains nothing, and a closed list when it does.
  if (isMultiple(parameter)) {
    return (
      <MultiValueInput
        {...inputProps(props, countProblems(parameter, values))}
        choices={choices}
        splitTyped={commaSeparated ? splitList : undefined}
        chipError={value => refusal(parameter, value) !== undefined}
      />
    );
  }
  return choices.length > 0
    ? <ChoiceSelect {...inputProps(props)} choices={choices} />
    : <TextInput {...inputProps(props)} />;
}
