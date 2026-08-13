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

// Typed-in text. Several values are typed one per line: a set of inputs that grow and shrink is a
// good deal more machinery, and a question that offers its answers is a choice question instead.
function TextAnswer({ question, values, disabled, onChange, onAnswered }: AnswerComponentProps) {
  const many = question.multiple;
  const helperText = many
    ? `${question.description ?? ""} One per line.`.trim()
    : question.description;

  return (
    <TextField
      label={questionLabel(question)}
      required={question.required}
      disabled={disabled}
      multiline={many}
      minRows={many ? 2 : undefined}
      fullWidth
      value={many ? values.join("\n") : values[0] ?? ""}
      slotProps={{ inputLabel: { shrink: true } }}
      helperText={helperText}
      onChange={event => onChange(many ? event.target.value.split("\n") : [ event.target.value ])}
      // Blank lines and a blank field are not answers; dropping them here is what makes clearing a
      // field store nothing rather than store an empty string
      onBlur={event => onAnswered(many
        ? event.target.value.split("\n").map(value => value.trim()).filter(Boolean)
        : [ event.target.value ].filter(Boolean))}
    />
  );
}

export const textAnswerCandidate: AnswerComponentCandidate = question =>
  question.dataType === "text" ? [ TextAnswer, 10 ] : null;

export default TextAnswer;
