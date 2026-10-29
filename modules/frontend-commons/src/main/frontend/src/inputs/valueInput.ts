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

// What every value input shares: its props, when its problems show, and when a typed value is committed.

import { useState } from "react";

// What every input takes. An input never saves: it reports values, and the caller decides what to keep.
export interface ValueInputProps {
  label: string;
  description?: string;
  required?: boolean;
  // Empty when unset, for a single-valued input as for a many-valued one
  values: string[];
  // Called on every change to the values, such as a keystroke or a pick
  onChange: (values: string[]) => void;
  // Called once a changed value is finished: when the field is left, Enter is pressed or a choice is picked
  onCommit?: (values: string[]) => void;
  // What is wrong with the values, one message each, such as "Enter a whole number."
  problems?: string[];
  disabled?: boolean;
  readOnly?: boolean;
}

// The problem shown once a required field is left without a value.
export const REQUIRED_PROBLEM = "A value is required";

// The one value of a single-valued field, or "" when it has none.
export const singleValue = (values: readonly string[]): string => values.at(0) ?? "";

// The values of a single-valued field holding this text: none when the text is empty.
export const fromSingle = (value: string): string[] => value === "" ? [] : [ value ];

// What a choice is reported with: one made from a list is finished the moment it is made. Values other than strings,
// such as a structure's records, are reported the same way.
export function picker<Value = string>({ onChange, onCommit }: {
  onChange: (values: Value[]) => void;
  onCommit?: (values: Value[]) => void;
}) {
  return (next: Value[]) => {
    onChange(next);
    onCommit?.(next);
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

// Whether a field has been left at least once, and the function recording that it has.
export function useVisited() {
  const [ visited, setVisited ] = useState(false);
  return [ visited, () => setVisited(true) ] as const;
}

// The props of the field an input renders, and the handler that marks it left.
export function useFieldProps(props: ValueInputProps) {
  const { label, description, required, values, problems = [], disabled } = props;
  const [ visited, visit ] = useVisited();
  // Flagged only once the field has been left, so that a form does not open with every field in error
  const missing = required && values.every(value => value === "");
  const currentProblems = visited && missing ? [ ...problems, REQUIRED_PROBLEM ] : problems;
  return {
    visit,
    field: {
      label,
      required,
      disabled,
      error: currentProblems.length > 0,
      helperText: currentProblems.length > 0 ? currentProblems.join(". ") : description,
      fullWidth: true,
    },
  };
}
