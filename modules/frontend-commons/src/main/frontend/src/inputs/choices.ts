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
