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
import { isMultiple, isRequired } from "../submissionForm";
import { getInputFrame } from "./answerFrame";
import AnswerRow from "./AnswerRow";
import QuestionText, { getQuestionTextId } from "./QuestionText";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// Typed-in text. Several values are typed one per line. A question that offers its answers is a ChoiceAnswer instead.
function TextAnswer(
  { question, values, disabled, onChange, onAnswered, suggested, aside }: AnswerComponentProps,
) {
  const many = isMultiple(question);
  const helperText = many
    ? `${question.description ?? ""} One per line.`.trim()
    : question.description;

  return (
    <Box>
      <QuestionText question={question} />
      <AnswerRow aside={aside}>
        <TextField
          required={isRequired(question)}
          disabled={disabled}
          multiline
          minRows={many ? 2 : 1}
          fullWidth
          sx={getInputFrame(suggested === true)}
          value={many ? values.join("\n") : values[0] ?? ""}
          slotProps={{ htmlInput: { "aria-labelledby": getQuestionTextId(question) } }}
          helperText={helperText}
          onChange={event => onChange(many ? event.target.value.split("\n") : [ event.target.value ])}
          // Blank lines and a blank field are not answers; dropping them here is what makes clearing a
          // field store nothing rather than store an empty string. Trimmed first, because the server
          // judges blankness the same way -- without it a field holding only spaces saves nothing,
          // reports Saved, and goes on sending the same non-answer every time it is left.
          onBlur={event => onAnswered(many
            ? event.target.value.split("\n").map(value => value.trim()).filter(Boolean)
            : [ event.target.value.trim() ].filter(Boolean))}
        />
      </AnswerRow>
    </Box>
  );
}

export const textAnswerCandidate: AnswerComponentCandidate = question =>
  question.dataType === "text" ? [ TextAnswer, 10 ] : null;

registerAnswerComponent(textAnswerCandidate);

export default TextAnswer;
