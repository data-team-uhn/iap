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

import {
  FormControl,
  FormControlLabel,
  FormHelperText,
  Radio,
  RadioGroup,
} from "@mui/material";

import { registerAnswerComponent } from "../answerComponents";
import { isRequired } from "../submissionForm";
import { getOptionFrame } from "./answerFrame";
import AnswerRow from "./AnswerRow";
import QuestionText, { getQuestionTextId } from "./QuestionText";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A yes or no answer, as a pair of radio buttons. A pick is a finished answer, so it is saved at once.
// A single checkbox would show "no" and "not answered yet" the same way.
function BooleanAnswer(
  { question, values, disabled, onAnswered, suggested, aside, corner }: AnswerComponentProps,
) {
  return (
    <FormControl
      required={isRequired(question)}
      disabled={disabled}
      component="fieldset"
      aria-labelledby={getQuestionTextId(question)}
      fullWidth
    >
      <QuestionText question={question} />
      <AnswerRow aside={aside} corner={corner}>
        {/* `required` is on each radio, since the FormControl does not forward it */}
        <RadioGroup
          row
          aria-labelledby={getQuestionTextId(question)}
          value={values[0] ?? ""}
          onChange={event => onAnswered([ event.target.value ])}
        >
          <FormControlLabel
            value="true"
            control={<Radio required={isRequired(question)} />}
            label="Yes"
            sx={getOptionFrame(suggested === true && values[0] === "true")}
          />
          <FormControlLabel
            value="false"
            control={<Radio required={isRequired(question)} />}
            label="No"
            sx={getOptionFrame(suggested === true && values[0] === "false")}
          />
        </RadioGroup>
      </AnswerRow>
      {question.description && <FormHelperText>{question.description}</FormHelperText>}
    </FormControl>
  );
}

export const booleanAnswerCandidate: AnswerComponentCandidate = question =>
  question.dataType === "boolean" ? [ BooleanAnswer, 50 ] : null;

registerAnswerComponent(booleanAnswerCandidate);

export default BooleanAnswer;
