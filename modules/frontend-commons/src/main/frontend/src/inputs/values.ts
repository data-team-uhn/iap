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

// The values a value input holds, and the problems it shows about them.

// The problem shown once a required field is left without a value.
export const REQUIRED_PROBLEM = "A value is required.";

// The problem shown once a date field is left with a day only partly entered.
export const DATE_PROBLEM = "Enter a date.";

// Whether a value is empty or only spaces, either of which counts as no value.
export const isBlank = (value: string): boolean => value.trim() === "";

// The one value of a single-valued field, or "" when it has none.
export const singleValue = (values: readonly string[]): string => values.at(0) ?? "";

// The values of a single-valued field holding this text: none when the text is empty.
export const fromSingle = (value: string): string[] => value === "" ? [] : [ value ];

// Reports a finished change, such as a pick or an added or removed chip, as a change and as a commit.
export function changeAndCommit<Value = string>({ onChange, onCommit }: {
  onChange: (values: Value[]) => void;
  onCommit?: (values: Value[]) => void;
}) {
  return (next: Value[]) => {
    onChange(next);
    onCommit?.(next);
  };
}

// What is wrong with how many values a list holds: fewer than a list that holds any must hold, or more than it
// may. Any number by default.
export function countProblems(count: number, minValues = 0, maxValues = Infinity): string[] {
  if (count > 0 && count < minValues) {
    return [ `At least ${minValues} values are needed.` ];
  }
  if (count <= maxValues) {
    return [];
  }
  return [ maxValues === 1 ? "At most 1 value is accepted." : `At most ${maxValues} values are accepted.` ];
}
