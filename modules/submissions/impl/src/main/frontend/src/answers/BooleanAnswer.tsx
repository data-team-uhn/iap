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
  FormLabel,
  Radio,
  RadioGroup,
} from "@mui/material";

import { registerAnswerComponent } from "../answerComponents";
import { questionLabel } from "./label";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A yes/no answer, always as a pair of choices. Picking one is a finished answer the moment it
// happens, so it saves on change rather than on blur, as other inputs normally do.
//
// A single checkbox would be the same control for "no" as for "not answered yet", which a question
// that may be left unanswered has to be able to tell apart.
function BooleanAnswer({ question, values, disabled, onAnswered }: AnswerComponentProps) {
  return (
    <FormControl required={question.required} disabled={disabled} component="fieldset">
      <FormLabel component="legend">{questionLabel(question)}</FormLabel>
      {/* required on each input, not on the FormControl, which never forwards it to a radio */}
      <RadioGroup row value={values[0] ?? ""} onChange={event => onAnswered([ event.target.value ])}>
        <FormControlLabel value="true" control={<Radio required={question.required} />} label="Yes" />
        <FormControlLabel value="false" control={<Radio required={question.required} />} label="No" />
      </RadioGroup>
      {question.description && <FormHelperText>{question.description}</FormHelperText>}
    </FormControl>
  );
}

export const booleanAnswerCandidate: AnswerComponentCandidate = question =>
  question.dataType === "boolean" ? [ BooleanAnswer, 50 ] : null;

registerAnswerComponent(booleanAnswerCandidate);

export default BooleanAnswer;
