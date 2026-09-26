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

import { Checkbox, FormControlLabel, FormHelperText, Stack } from "@mui/material";

import { isRequired } from "../submissionForm";
import { getOptionFrame } from "./answerFrame";
import AnswerRow from "./AnswerRow";
import QuestionText, { getQuestionTextId } from "./QuestionText";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A yes/no answer. A tick is a finished answer the moment it happens — there is nothing to leave —
// so it saves on change rather than on blur, which is what every other input here waits for.
function BooleanAnswer({ question, values, disabled, onAnswered, suggested, aside }: AnswerComponentProps) {
  return (
    <Stack>
      <QuestionText question={question} />
      <AnswerRow aside={aside}>
        <FormControlLabel
          control={
            <Checkbox
              checked={values[0] === "true"}
              disabled={disabled}
              required={isRequired(question)}
              slotProps={{ input: { "aria-labelledby": getQuestionTextId(question) } }}
              onChange={event => onAnswered([ String(event.target.checked) ])}
            />
          }
          label="Yes"
          sx={{ alignSelf: "flex-start", ...getOptionFrame(suggested === true && values[0] === "true") }}
        />
      </AnswerRow>
      {question.description && <FormHelperText>{question.description}</FormHelperText>}
    </Stack>
  );
}

export const booleanAnswerCandidate: AnswerComponentCandidate = question =>
  question.dataType === "boolean" ? [ BooleanAnswer, 50 ] : null;

export default BooleanAnswer;
