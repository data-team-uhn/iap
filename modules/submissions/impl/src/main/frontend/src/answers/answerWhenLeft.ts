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

import type { FocusEvent } from "react";

import type { AnswerComponentProps } from "../answerComponents";

/**
 * The blur handler of a field whose text the browser parses, such as a day or a number: it answers
 * the field's value when the field is left. A value the browser cannot parse reads as "", like a
 * cleared field, and would clear the stored answer. `badInput` tells the two apart, so such a value
 * is not answered.
 */
export function answerWhenLeft(onAnswered: AnswerComponentProps["onAnswered"]) {
  return (event: FocusEvent<HTMLInputElement | HTMLTextAreaElement>): void => {
    if (!event.target.validity.badInput) {
      onAnswered([ event.target.value ].filter(Boolean));
    }
  };
}
