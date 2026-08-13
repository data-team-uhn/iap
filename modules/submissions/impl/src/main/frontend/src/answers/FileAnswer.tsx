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

import { Stack, Typography } from "@mui/material";

import { questionLabel } from "./label";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A question asking for a file, which cannot be answered here yet: uploading through the workflow
// engine is its own mechanism and is not built.
//
// This says so rather than leaving the question out. A form that silently omits a question it cannot
// ask reads as complete when it is not, and a text box in its place would store a filename that
// nothing points at — a wrong answer being easier to give than no answer at all.
function FileAnswer({ question }: AnswerComponentProps) {
  return (
    <Stack>
      <Typography variant="subtitle2">{questionLabel(question)}</Typography>
      <Typography variant="body2" color="text.secondary">
        Attaching a file is not available yet.
      </Typography>
    </Stack>
  );
}

export const fileAnswerCandidate: AnswerComponentCandidate = question =>
  question.dataType === "file" ? [ FileAnswer, 50 ] : null;

export default FileAnswer;
