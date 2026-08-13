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

import { createElement, useState } from "react";

import ErrorOutlinedIcon from "@mui/icons-material/ErrorOutlined";
import { Box, CircularProgress, Tooltip, Typography } from "@mui/material";

import { getAnswerComponent } from "./answerComponents";
import { registerBuiltinAnswerComponents } from "./answers";
import { questionLabel } from "./answers/label";

import type { FormQuestion } from "./submissionForm";

registerBuiltinAnswerComponents();

export type SaveState = "idle" | "saving" | "saved" | "failed";

interface AnswerFieldProps {
  question: FormQuestion;
  state: SaveState;
  error?: string;
  disabled?: boolean;
  // Called when the answer is *complete* — a field left, a box ticked, an option picked — rather
  // than on every keystroke. That is what makes saving as-you-go bearable, and it is also what keeps
  // the saved answers current enough for the server to re-decide which questions apply.
  onAnswered: (values: string[]) => void;
}

// What a save is currently doing, shown per field because that is where it can fail: a request may
// be refused because somebody submitted this request in another tab, and a field that looked saved
// would be a lie.
function SaveStatus({ state, error }: { state: SaveState; error?: string }) {
  if (state === "saving") {
    return <CircularProgress size={16} aria-label="Saving" />;
  }
  if (state === "saved") {
    return <Typography variant="caption" color="text.secondary">Saved</Typography>;
  }
  if (state === "failed") {
    return (
      <Tooltip title={error ?? "This answer was not saved"}>
        <ErrorOutlinedIcon color="error" fontSize="small" aria-label="Not saved" />
      </Tooltip>
    );
  }
  return null;
}

// One question and its answer.
//
// What kind of input a question gets is not decided here: each answer component says which questions
// it recognizes and how well (see answerComponents.ts), so adding a kind of question is adding a
// component rather than another branch in this one. What stays here is everything that is the same
// whatever is being answered — following the saved answer, deciding whether anything actually
// changed, and reporting what the save is doing.
function AnswerField({ question, state, error, disabled, onAnswered }: AnswerFieldProps) {
  const [ draft, setDraft ] = useState(question.value);
  // The server is the authority on what the answer is: it re-reads the whole form after every save,
  // and an answer changed elsewhere should appear here. Adjusted while rendering, which is React's
  // own way of following a prop and avoids the extra pass an effect would cost.
  //
  // Compared by content rather than by identity, which matters: each read returns fresh arrays, so
  // an identity check would reset *every* field on *every* save — including one somebody is halfway
  // through typing in, whose keystrokes would vanish because a different field was saved.
  // Joined on a character an answer cannot contain, so that ["a", "b"] and ["a b"] stay distinct.
  const answered = question.value.join("\u0000");
  const [ seen, setSeen ] = useState(answered);
  if (seen !== answered) {
    setSeen(answered);
    setDraft(question.value);
  }

  const changed = (values: string[]) =>
    values.length !== question.value.length || values.some((value, index) => value !== question.value[index]);
  // Saving an answer that did not change would be a workflow event that means nothing
  const submit = (values: string[]) => {
    setDraft(values);
    if (changed(values)) {
      onAnswered(values);
    }
  };

  const Answer = getAnswerComponent(question);
  if (!Answer) {
    // Said out loud rather than skipped: a form that silently drops a question it cannot ask reads
    // as complete when it is not
    return (
      <Box>
        <Typography variant="subtitle2">{questionLabel(question)}</Typography>
        <Typography variant="body2" color="text.secondary">
          {`This question asks for ${question.dataType}, which cannot be answered here.`}
        </Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1 }}>
      {/* Built through createElement rather than as <Answer/>: which component this is depends on the
          question, and JSX on a value looks to the compiler like a component being defined here on
          every render. The registry hands back the same function each time, so nothing remounts. */}
      <Box sx={{ flexGrow: 1 }}>
        {createElement(Answer, {
          question,
          values: draft,
          disabled: Boolean(disabled),
          onChange: setDraft,
          onAnswered: submit,
        })}
      </Box>
      <Box sx={{ pt: 2 }}><SaveStatus state={state} error={error} /></Box>
    </Box>
  );
}

export default AnswerField;
