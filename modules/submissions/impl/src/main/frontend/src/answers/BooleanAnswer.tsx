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
import { questionLabel } from "./label";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A yes/no answer. A tick is a finished answer the moment it happens — there is nothing to leave —
// so it saves on change rather than on blur, which is what every other input here waits for.
function BooleanAnswer({ question, values, disabled, onAnswered }: AnswerComponentProps) {
  return (
    <Stack>
      <FormControlLabel
        control={
          <Checkbox
            checked={values[0] === "true"}
            disabled={disabled}
            required={isRequired(question)}
            onChange={event => onAnswered([ String(event.target.checked) ])}
          />
        }
        label={questionLabel(question)}
      />
      {question.description && <FormHelperText>{question.description}</FormHelperText>}
    </Stack>
  );
}

export const booleanAnswerCandidate: AnswerComponentCandidate = question =>
  question.dataType === "boolean" ? [ BooleanAnswer, 50 ] : null;

export default BooleanAnswer;
