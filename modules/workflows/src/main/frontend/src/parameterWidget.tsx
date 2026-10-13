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

import type { ComponentType } from "react";

import { REQUIRED_PROBLEM } from "@iap/frontend-commons/inputs/valueInput";
import type { ValueInputProps } from "@iap/frontend-commons/inputs/valueInput";

import { refusal, textValues, usableValues } from "./parameterValues";
import { isRequired } from "./workflowTypes";

import type { ParameterValue } from "./parameterValues";
import type { Parameter } from "./workflowTypes";

// What a field editing one parameter is given, whatever the parameter's type. A value is text, except in
// the field for a structure, which is given its records.
export interface ParameterFieldProps<Value = string> {
  parameter: Parameter;
  // Always a list, whatever the parameter's cardinality: empty means unset.
  values: Value[];
  // While a value is being typed, so that what was typed is what is shown
  onChange: (values: Value[]) => void;
  // When a value is finished: a field left, Enter pressed, an option picked. This is what is written into
  // the diagram, so each finished edit is one step to undo rather than one per keystroke
  onCommit: (values: Value[]) => void;
  // Whether the list is stored joined with commas. A comma typed into a value then starts a second one,
  // so the field splits it there rather than showing one chip that rereads as two.
  commaSeparated?: boolean;
  readOnly?: boolean;
}

export type ParameterWidget = ComponentType<ParameterFieldProps>;

// What a value the server would drop is flagged with, as it would report it.
export const refusals = (parameter: Parameter, values: readonly ParameterValue[]): string[] =>
  textValues(values).flatMap(value => refusal(parameter, value) ?? []).slice(0, 1);

// What is wrong with a structure's values: a value the server would drop, and a required parameter left with no
// value it can store, once the field has been visited. A field of text leaves the second to its input.
export function valueProblems(parameter: Parameter, values: readonly ParameterValue[], visited: boolean): string[] {
  const missing = visited && isRequired(parameter) && usableValues(parameter, values).length === 0;
  return [ ...refusals(parameter, values), ...missing ? [ REQUIRED_PROBLEM ] : [] ];
}

// What an input of the frontend commons is given of a parameter and of what is wrong with its values.
export const inputProps = (props: ParameterFieldProps, problems: readonly string[] = []): ValueInputProps => ({
  label: props.parameter.label,
  description: props.parameter.description,
  required: isRequired(props.parameter),
  values: props.values,
  onChange: props.onChange,
  onCommit: props.onCommit,
  problems: [ ...refusals(props.parameter, props.values), ...problems ],
  readOnly: props.readOnly,
});
