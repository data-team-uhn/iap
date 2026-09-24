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
import AnswerProvenance, { ConfirmAnswer } from "./AnswerProvenance";
import { useAnswerComponents } from "./answers";
import { questionLabel } from "./answers/label";
import QuestionText from "./answers/QuestionText";
import { statusOf } from "./provenance";

import type { FormQuestion } from "./submissionForm";

// "reviewFailed" is distinct from "failed": confirming a suggestion or rejecting its evidence never
// touches the answer itself, so a refused review must not read as the saved answer having failed.
export type SaveState = "idle" | "saving" | "saved" | "failed" | "reviewFailed";

interface AnswerFieldProps {
  question: FormQuestion;
  state: SaveState;
  error?: string;
  disabled?: boolean;
  // Called when the answer is complete, meaning a field left, a box ticked or an option picked, rather
  // than on every keystroke. That is what makes saving as-you-go bearable, and it is also what keeps
  // the saved answers current enough for the server to re-decide which questions apply.
  onAnswered: (values: string[]) => void;
  // Called when the submitter accepts a pre-filled answer as it stands, and when they say the cited
  // passage does not support it. Both are absent for a question nothing suggested an answer to.
  onAcceptSuggestion?: () => void;
  onRejectEvidence?: (rejected: boolean) => void;
}

// What a save is currently doing, shown per field because that is where it can fail. A request may
// be refused because somebody submitted this request in another tab, and a field that looked saved
// would be a lie.
function SaveStatus({ state, error }: { state: SaveState; error?: string }) {
  if (state === "saving") {
    return <CircularProgress size={16} aria-label="Saving" />;
  }
  if (state === "saved") {
    return <Typography variant="caption">Saved</Typography>;
  }
  if (state === "failed" || state === "reviewFailed") {
    // With no Save button this is the only report that an answer, or a review of it, was refused, so
    // it cannot live on the icon: SvgIcon marks itself aria-hidden, which hides anything said through
    // it. The text carries the outcome, and the wrapper takes focus so the reason is reachable from a
    // keyboard. The two states read differently: the answer itself is untouched by a refused review.
    const fallback = state === "reviewFailed" ? "This review could not be recorded" : "This answer was not saved";
    const caption = state === "reviewFailed" ? "Review failed" : "Not saved";
    return (
      <Tooltip title={error ?? fallback}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }} tabIndex={0}>
          <ErrorOutlinedIcon color="error" fontSize="small" />
          <Typography variant="caption" color="error">{caption}</Typography>
        </Box>
      </Tooltip>
    );
  }
  return null;
}

// One question and its answer.
//
// What kind of input a question gets is not decided here. Each answer component says which questions
// it recognizes and how well (see answerComponents.ts). Adding a kind of question is adding a
// component rather than another branch in this one. What stays here is what is the same whatever is
// being answered: following the saved answer, noticing a change, and reporting what the save does.
function AnswerField(
  { question, state, error, disabled, onAnswered, onAcceptSuggestion, onRejectEvidence }: AnswerFieldProps,
) {
  const ready = useAnswerComponents();
  const [ draft, setDraft ] = useState(question.value);
  const [ focused, setFocused ] = useState(false);
  // The server is the authority on what the answer is: it re-reads the whole form after every save,
  // and an answer changed elsewhere should appear here. Adjusted while rendering, which is React's
  // own way of following a prop and avoids the extra pass an effect would cost.
  //
  // Compared by content rather than by identity, because each read returns fresh arrays. An identity
  // check would reset every field on every save, including one somebody is halfway through typing
  // in, whose keystrokes would vanish because a different field was saved.
  // Joined on a character an answer cannot contain, so that ["a", "b"] and ["a b"] stay distinct.
  const answered = question.value.join("\u0000");
  const [ seen, setSeen ] = useState(answered);
  // Held back while this field has focus. Clearing an answer changes the server's value, so without this a
  // submitter who empties a field and immediately types again has the field blanked mid-word when the re-read
  // lands. `seen` is left alone too, so the new value is applied on the first render after they leave.
  // Held back while this field's own save is going too: the value the server has then is the one before it.
  if (seen !== answered && !focused && state !== "saving") {
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

  if (!ready) {
    // Not the "cannot be answered here" message below: until the components have been fetched,
    // nothing is registered yet, and saying the question cannot be answered would be untrue
    return (
      <Box>
        <Typography variant="subtitle2">{questionLabel(question)}</Typography>
        <CircularProgress size={16} aria-label="Loading the answer field" />
      </Box>
    );
  }

  const Answer = getAnswerComponent(question);
  if (!Answer) {
    // Said out loud rather than skipped: a form that silently drops a question it cannot ask reads
    // as complete when it is not
    return (
      <Box>
        <QuestionText question={question} labelOnly />
        <Typography variant="description">
          {`This question asks for ${question.dataType}, which cannot be answered here.`}
        </Typography>
      </Box>
    );
  }

  // An answer the model drafted and nobody has checked yet is framed, so the ones still to check
  // stand out. The answer component draws the frame round the answer itself.
  const pending = question.provenance !== undefined
    && statusOf(question.provenance, question.value) === "suggested";

  return (
    <Box
      onFocusCapture={() => setFocused(true)}
      onBlurCapture={() => setFocused(false)}
      sx={{
        // The question with its answer, then the evidence; one column on a phone. The confirm
        // button sits inside the first, beside the answer it confirms.
        display: "grid",
        gridTemplateColumns: question.provenance
          ? { xs: "minmax(0, 1fr)", md: "minmax(0, 3fr) minmax(0, 2fr)" }
          : "minmax(0, 1fr)",
        columnGap: 2,
        rowGap: 1,
        alignItems: "start",
      }}
    >
      <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1, minWidth: 0 }}>
        {/* Built through createElement rather than as <Answer/>, because which component this is comes
            from the registry and so is only known during the render that uses it. In JSX that is what
            react-hooks/static-components refuses: "Cannot create components during render" */}
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          {createElement(Answer, {
            question,
            values: draft,
            disabled: Boolean(disabled),
            onChange: setDraft,
            onAnswered: submit,
            suggested: pending,
            aside: question.provenance
              ? (
                <ConfirmAnswer
                  provenance={question.provenance}
                  value={question.value}
                  disabled={disabled}
                  onAccept={() => onAcceptSuggestion?.()}
                />
              )
              : undefined,
          })}
        </Box>
        <Box sx={{ pt: 4 }}><SaveStatus state={state} error={error} /></Box>
      </Box>
      {question.provenance
        ? (
          <AnswerProvenance
            provenance={question.provenance}
            value={question.value}
            disabled={disabled}
            onRejectEvidence={rejected => onRejectEvidence?.(rejected)}
          />
        )
        : null}
    </Box>
  );
}

export default AnswerField;
