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

export interface Choice {
  value: string;
  label: string;
}

/** The label of the choice holding this value. Falls back to the value if not found. */
export const choiceLabel = (choices: readonly Choice[], value: string): string =>
  choices.find(choice => choice.value === value)?.label ?? value;

/**
 * The choices, plus the current value if the choices do not contain it.
 *
 * @param choices the known choices
 * @param current the value picked now
 * @param labelOf how to label the current value when it is added
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
 * The choices, plus each of the values the choices do not contain, so that a stored value the list no longer offers
 * stays shown and selected.
 *
 * @param choices the known choices
 * @param values the values picked now
 * @param labelOf how to label a value that is added
 * @returns the choices, with the added values last
 */
export const withValues = (
  choices: Choice[],
  values: readonly string[],
  labelOf?: (value: string) => string
): Choice[] => values.reduce((list, value) => withCurrent(list, value, labelOf), choices);

export const booleanChoices = (trueLabel = "Yes", falseLabel = "No"): Choice[] =>
  [ { value: "true", label: trueLabel }, { value: "false", label: falseLabel } ];
