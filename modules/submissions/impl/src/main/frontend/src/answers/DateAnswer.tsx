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

import { Box, TextField } from "@mui/material";

import { registerAnswerComponent } from "../answerComponents";
import { isRequired } from "../submissionForm";
import AnswerRow from "./AnswerRow";
import QuestionText, { getQuestionTextId } from "./QuestionText";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A calendar day, stored as `yyyy-mm-dd` whatever the reader's locale displays. It is saved when the
// field is left, since a day can also be typed in digit by digit.
function DateAnswer({ question, values, disabled, onChange, onAnswered, aside }: AnswerComponentProps) {
  return (
    <Box>
      <QuestionText question={question} />
      <AnswerRow aside={aside}>
        <TextField
          type="date"
          required={isRequired(question)}
          disabled={disabled}
          fullWidth
          value={values[0] ?? ""}
          slotProps={{ htmlInput: { "aria-labelledby": getQuestionTextId(question) } }}
          helperText={question.description}
          onChange={event => onChange([ event.target.value ])}
          // A half-typed day reads as "", like a cleared field, and would clear the stored answer.
          // `badInput` tells the two apart.
          onBlur={event => {
            if (!event.target.validity.badInput) {
              onAnswered([ event.target.value ].filter(Boolean));
            }
          }}
        />
      </AnswerRow>
    </Box>
  );
}

export const dateAnswerCandidate: AnswerComponentCandidate = question =>
  question.dataType === "date" ? [ DateAnswer, 50 ] : null;

registerAnswerComponent(dateAnswerCandidate);

export default DateAnswer;
