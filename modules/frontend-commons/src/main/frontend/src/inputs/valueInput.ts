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

import { useState } from "react";

// What every input takes. An input never saves: it reports values, and the caller decides what to keep.
export interface ValueInputProps {
  label: string;
  description?: string;
  required?: boolean;
  // Empty means unset, whatever the cardinality
  values: string[];
  // Called while a value is typed
  onChange: (values: string[]) => void;
  // Called when a value is finished: the field left, Enter pressed, a choice picked. A pick calls both.
  onCommit?: (values: string[]) => void;
  // What is wrong with the values, one message each, such as "Enter a whole number."
  problems?: string[];
  disabled?: boolean;
  readOnly?: boolean;
}

// What a missing value is flagged with.
export const REQUIRED_PROBLEM = "A value is required";

// The value a single valued field shows.
export const singleValue = (values: readonly string[]): string => values.at(0) ?? "";

// An array from a single valued field, replacing an empty input with an empty array.
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

// The handlers that finish a typed value: leaving the field, or pressing Enter in it. The commit is given the
// field's element.
export const commitProps = (commit: (target: EventTarget) => void) => ({
  onBlur: (event: { target: EventTarget }) => commit(event.target),
  onKeyDown: (event: { key: string; target: EventTarget }) => {
    if (event.key === "Enter") {
      commit(event.target);
    }
  },
});

// Whether a field has been left at least once.
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
  const shown = visited && missing ? [ ...problems, REQUIRED_PROBLEM ] : problems;
  return {
    visit,
    field: {
      label,
      required,
      disabled,
      error: shown.length > 0,
      helperText: shown.length > 0 ? shown.join(". ") : description,
      fullWidth: true,
    },
  };
}
