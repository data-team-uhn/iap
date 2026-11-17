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

import { useRef, useState } from "react";

// The props every value input takes.
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
  // What is wrong with the values, one sentence each, such as "Enter a whole number."; shown in place of the
  // description
  problems?: string[];
  disabled?: boolean;
  readOnly?: boolean;
}

// The problem shown once a required field is left without a value.
export const REQUIRED_PROBLEM = "A value is required.";

// Whether a value is empty or only spaces, either of which counts as no value.
export const isBlank = (value: string): boolean => value.trim() === "";

// The one value of a single-valued field, or "" when it has none.
export const singleValue = (values: readonly string[]): string => values.at(0) ?? "";

// The values of a single-valued field holding this text: none when the text is empty.
export const fromSingle = (value: string): string[] => value === "" ? [] : [ value ];

// Reports a pick from a list as a change and as a commit, since a pick is finished as soon as it is made.
export function changeAndCommit<Value = string>({ onChange, onCommit }: {
  onChange: (values: Value[]) => void;
  onCommit?: (values: Value[]) => void;
}) {
  return (next: Value[]) => {
    onChange(next);
    onCommit?.(next);
  };
}

// The event handlers that finish a typed value: leaving the field, or pressing Enter in it. Each passes
// `commit` the field's element.
export const commitProps = (commit: (target: EventTarget) => void) => ({
  onBlur: (event: { target: EventTarget }) => commit(event.target),
  onKeyDown: (event: { key: string; target: EventTarget }) => {
    if (event.key === "Enter") {
      commit(event.target);
    }
  },
});

// Whether a field has been left at least once, and the function recording that it has.
export function useVisited() {
  const [ visited, setVisited ] = useState(false);
  return [ visited, () => setVisited(true) ] as const;
}

const sameValues = (a: readonly string[], b: readonly string[]): boolean =>
  a.length === b.length && a.every((value, index) => value === b[index]);

/**
 * The props of the field an input renders, and the functions that record the field left and finish its value.
 *
 * @param props the input's own props
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
  const currentProblems = [ ...new Set([ ...problems, ...flagged ]) ];
  return {
    visit,
    // Marks the field left and reports its values, unless they were reported already
    commit: () => {
      visit();
      if (!sameValues(values, committed.current)) {
        committed.current = values;
        onCommit?.(values);
      }
    },
    field: {
      label,
      required,
      disabled,
      error: currentProblems.length > 0,
      helperText: currentProblems.length > 0 ? currentProblems.join(" ") : description,
      fullWidth: true,
      // A commit compares with the value the field holds on entry. The caller may have changed it since the
      // last commit, by an undo for example.
      onFocus: () => {
        committed.current = values;
      },
    },
  };
}
