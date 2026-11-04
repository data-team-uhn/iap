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

import { useRef, useState } from "react";

// What every input takes.
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
  // What is wrong with the values, one sentence each, such as "Enter a whole number."
  problems?: string[];
  disabled?: boolean;
  readOnly?: boolean;
}

// What a missing value is flagged with.
export const REQUIRED_PROBLEM = "A value is required.";

// Whether a value holds nothing but spaces, which counts as no value.
export const isBlank = (value: string): boolean => value.trim() === "";

// The value a single valued field shows.
export const singleValue = (values: readonly string[]): string => values.at(0) ?? "";

// An array from a single valued field, replacing an empty input with an empty array.
export const fromSingle = (value: string): string[] => value === "" ? [] : [ value ];

// Reports a choice made from a list, which is finished the moment it is made. Values other than strings are
// reported the same way.
export function changeAndCommit<Value = string>({ onChange, onCommit }: {
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

const sameValues = (a: readonly string[], b: readonly string[]): boolean =>
  a.length === b.length && a.every((value, index) => value === b[index]);

/**
 * The props of the field an input renders, and how the input marks it left and finishes a value.
 *
 * @param props what the input was given
 * @param onceLeft problems shown only once the field has been left, such as a value not finished yet
 */
export function useFieldProps(props: ValueInputProps, onceLeft: string[] = []) {
  const { label, description, required, values, onCommit, problems = [], disabled } = props;
  const [ visited, visit ] = useVisited();
  const committed = useRef(values);
  // Flagged only once the field has been left, so that a form does not open with every field in error
  const missing = required && values.every(isBlank);
  const flagged = visited ? [ ...onceLeft, ...missing ? [ REQUIRED_PROBLEM ] : [] ] : [];
  // A message several sources agree on is shown once
  const shown = [ ...new Set([ ...problems, ...flagged ]) ];
  return {
    visit,
    // Marks the field left and reports its values, unless they are not acceptable or were reported already
    commit: (accept = true) => {
      visit();
      if (accept && !sameValues(values, committed.current)) {
        committed.current = values;
        onCommit?.(values);
      }
    },
    field: {
      label,
      required,
      disabled,
      error: shown.length > 0,
      helperText: shown.length > 0 ? shown.join(" ") : description,
      fullWidth: true,
    },
  };
}
