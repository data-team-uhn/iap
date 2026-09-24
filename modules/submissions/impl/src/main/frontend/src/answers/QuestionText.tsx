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

import { Typography } from "@mui/material";

import { type FormQuestion, isRequired } from "../submissionForm";
import { questionLabel } from "./label";

// The id the question text carries, so the input under it can name it as its label.
export function getQuestionTextId(question: FormQuestion): string {
  return `${question.path}-text`;
}

// The question itself, printed on its own line above whatever answers it, in the body text colour.
// Kept out of the input's outline: a long question squeezed into a field label is small, grey and cut off.
function QuestionText({ question, labelOnly }: { question: FormQuestion; labelOnly?: boolean }) {
  return (
    <>
      <Typography
        id={labelOnly ? undefined : getQuestionTextId(question)}
        variant="subtitle1"
        component="div"
        sx={{ fontWeight: 500, mb: 0.5 }}
      >
        {questionLabel(question)}
        {!labelOnly && isRequired(question) ? " *" : ""}
      </Typography>
      {question.purpose
        ? <Typography variant="description" sx={{ display: "block", mb: 0.5 }}>{question.purpose}</Typography>
        : null}
    </>
  );
}

export default QuestionText;
