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

import { type KeyboardEvent, useRef, useState } from "react";

import { isBlank, REQUIRED_PROBLEM } from "./values";

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

// Whether a field has been left at least once, and the function recording that it has.
export function useVisited() {
  const [ visited, setVisited ] = useState(false);
  return [ visited, () => setVisited(true) ] as const;
}

/**
 * The props of the field an input renders, and the function recording that the field was left.
 *
 * @param props the input's own props
 * @param onceLeft problems shown only once the field has been left, such as a value not finished yet
 */
export function useFieldProps(props: ValueInputProps, onceLeft: string[] = []) {
  const { label, description, required, values, problems = [], disabled } = props;
  const [ visited, visit ] = useVisited();
  // Flagged only once the field has been left, so that a form does not open with every field in error
  const missing = required && values.every(isBlank);
  const currentProblems = [ ...problems, ...visited ? [ ...onceLeft, ...missing ? [ REQUIRED_PROBLEM ] : [] ] : [] ];
  return {
    visit,
    field: {
      label,
      required,
      disabled,
      error: currentProblems.length > 0,
      helperText: currentProblems.length > 0 ? currentProblems.join(" ") : description,
      fullWidth: true,
    },
  };
}

const sameValues = (a: readonly string[], b: readonly string[]): boolean =>
  a.length === b.length && a.every((value, index) => value === b[index]);

interface CommitOptions {
  values: string[];
  onCommit?: (values: string[]) => void;
  // Whether the values are finished; unfinished ones are not committed. By default, they are.
  finished?: boolean;
}

/**
 * The handlers that finish a typed value: leaving the field, or pressing Enter in it. Each marks the field
 * left, and commits the values when they are finished and differ from the last ones committed.
 *
 * @param options the values, where they are committed, and whether they are finished
 * @param visit records that the field was left
 */
export function useCommit({ values, onCommit, finished = true }: CommitOptions, visit: () => void) {
  const committed = useRef(values);
  const lastLeft = useRef(values);
  const commit = () => {
    visit();
    if (finished && !sameValues(values, committed.current)) {
      committed.current = values;
      onCommit?.(values);
    }
  };
  return {
    // Values changed from outside while the field was not focused, by an undo for example, count as committed
    onFocus: () => {
      if (!sameValues(values, lastLeft.current)) {
        committed.current = values;
      }
    },
    onBlur: () => {
      commit();
      lastLeft.current = values;
    },
    onKeyDown: (event: KeyboardEvent) => {
      // Enter pressed to confirm a word in an input method does not finish the value
      if (event.key === "Enter" && !event.nativeEvent.isComposing) {
        commit();
      }
    },
  };
}
