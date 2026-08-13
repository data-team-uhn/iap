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

import { TextField } from "@mui/material";

import { questionLabel } from "./label";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A calendar day.
//
// A date input stores what it shows as `yyyy-mm-dd` whatever the reader's locale displays, which is
// what makes it safe to compare: a condition matching a date compares the stored string, and any
// format that varied by who typed it would compare differently for different people. Picking a day
// finishes the answer, but typing one digit at a time does not, so this still saves on blur.
function DateAnswer({ question, values, disabled, onChange, onAnswered }: AnswerComponentProps) {
  return (
    <TextField
      label={questionLabel(question)}
      type="date"
      required={question.required}
      disabled={disabled}
      fullWidth
      value={values[0] ?? ""}
      slotProps={{ inputLabel: { shrink: true } }}
      helperText={question.description}
      onChange={event => onChange([ event.target.value ])}
      onBlur={event => onAnswered([ event.target.value ].filter(Boolean))}
    />
  );
}

export const dateAnswerCandidate: AnswerComponentCandidate = question =>
  question.dataType === "date" ? [ DateAnswer, 50 ] : null;

export default DateAnswer;
