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

import { isRequired } from "../submissionForm";
import { questionLabel } from "./label";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A number. The two numeric data types differ here rather than sharing one input: `long` steps in
// whole numbers and refuses a decimal point, `double` does neither. That is the difference the
// schema is stating by asking for one rather than the other, and a single "number" input would
// leave a submitter to discover it from a rejected save instead.
function NumberAnswer({ question, values, disabled, onChange, onAnswered }: AnswerComponentProps) {
  const whole = question.dataType === "long";

  return (
    <TextField
      label={questionLabel(question)}
      type="number"
      required={isRequired(question)}
      disabled={disabled}
      fullWidth
      value={values[0] ?? ""}
      slotProps={{
        inputLabel: { shrink: true },
        // `any` is the HTML default and is what allows decimals; `1` is what makes a browser refuse
        // them, which is the whole point of asking for a long.
        // The schema's bounds become the input's own hints; the save is what enforces them.
        htmlInput: {
          step: whole ? 1 : "any",
          inputMode: whole ? "numeric" : "decimal",
          min: question.minValue,
          max: question.maxValue,
        },
      }}
      helperText={question.description}
      onChange={event => onChange([ event.target.value ])}
      onBlur={event => onAnswered([ event.target.value ].filter(Boolean))}
    />
  );
}

export const numberAnswerCandidate: AnswerComponentCandidate = question =>
  question.dataType === "long" || question.dataType === "double" ? [ NumberAnswer, 50 ] : null;

export default NumberAnswer;
