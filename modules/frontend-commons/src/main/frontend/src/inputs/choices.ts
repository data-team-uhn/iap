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

// The entries of a list to pick from, and the helpers that label a value by them.

/** One entry of a list to pick from. */
export interface Choice {
  /** What is stored when it is picked. */
  value: string;
  /** What the list shows for it. */
  label: string;
}

/** The label of the choice holding this value, or the value itself when it is not one of the offered choices. */
export const choiceLabel = (choices: readonly Choice[], value: string): string =>
  choices.find(choice => choice.value === value)?.label ?? value;

/**
 * The choices, plus the current value when it is not one of them, so that a stored value the list no longer offers
 * stays shown and selected.
 *
 * @param choices the known choices
 * @param current the value picked now; nothing is added when it is `undefined` or empty
 * @param labelOf how to label the current value when it is added; by default, the value is its own label
 * @returns the choices, with the current value last when it was added
 */
export const withCurrent = (
  choices: Choice[],
  current?: string,
  labelOf = (value: string) => value
): Choice[] =>
  (current === undefined || current === "" || choices.some(choice => choice.value === current)
    ? choices : [ ...choices, { value: current, label: labelOf(current) } ]);

/**
 * The choices, plus each value that is not one of them.
 *
 * @param choices the known choices
 * @param values the values picked now
 * @param labelOf how to label a value that is added; by default, the value is its own label
 * @returns the choices, with the added values last
 */
export const withValues = (
  choices: Choice[],
  values: readonly string[],
  labelOf?: (value: string) => string
): Choice[] => values.reduce((list, value) => withCurrent(list, value, labelOf), choices);

/** Yes and No, stored as `"true"` and `"false"`, under other labels when given. */
export const booleanChoices = (trueLabel = "Yes", falseLabel = "No"): Choice[] =>
  [ { value: "true", label: trueLabel }, { value: "false", label: falseLabel } ];
