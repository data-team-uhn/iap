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

import { MenuItem, Typography } from "@mui/material";

import { isRequired } from "./workflowTypes";

import type { Parameter } from "./workflowTypes";

// What a field editing one parameter is given, whatever the parameter's type.
export interface ParameterFieldProps {
  parameter: Parameter;
  // Always a list, whatever the parameter's cardinality: empty means unset.
  values: string[];
  // While a value is being typed, so that what was typed is what is shown
  onChange: (values: string[]) => void;
  // When a value is finished: a field left, Enter pressed, an option picked. This is what is written into
  // the diagram, so each finished edit is one step to undo rather than one per keystroke
  onCommit: (values: string[]) => void;
  // Whether the list is stored joined with commas. A comma typed into a value then starts a second one,
  // so the field splits it there rather than showing one chip that rereads as two.
  commaSeparated?: boolean;
  readOnly?: boolean;
}

export type ParameterWidget = ComponentType<ParameterFieldProps>;

// What a choice is reported with: one made from a list or a button is finished the moment it is made.
export function picker({ onChange, onCommit }: Pick<ParameterFieldProps, "onChange" | "onCommit">) {
  return (next: string[]) => {
    onChange(next);
    onCommit(next);
  };
}

// The handlers that finish a typed value: leaving the field, or pressing Enter in it.
export const commitProps = (commit: () => void) => ({
  onBlur: commit,
  onKeyDown: (event: { key: string }) => {
    if (event.key === "Enter") {
      commit();
    }
  },
});

// What every parameter's field shows of the parameter itself.
export const baseFieldProps = (parameter: Parameter) => ({
  label: parameter.label,
  required: isRequired(parameter),
  helperText: parameter.description,
  fullWidth: true,
});

// The choice that leaves a select unset. A function rather than a component: a select reads the value
// off each of its children's own props.
export const noneItem = () => <MenuItem value=""><em>None</em></MenuItem>;

// What is shown in place of a value that has none, so that a read-only panel does not read as though
// the row were missing rather than empty.
const NOTHING = "—";

export const optionLabel = (parameter: Parameter, value: string): string =>
  parameter.options.find(option => option.value === value)?.label ?? value;

// A parameter shown as text rather than as a field, under its label.
export function ParameterText(props: { parameter: Parameter; shown: string[] }) {
  const { parameter, shown } = props;
  return <Typography>{parameter.label}: {shown.length === 0 ? NOTHING : shown.join(", ")}</Typography>;
}
