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
import QuestionText from "./answers/QuestionText";
import NewerReadingSwitch from "./NewerReadingSwitch";
import { getNewerOffer, getStatus, isPrefilled, isSameValues } from "./provenance";

import type { FormQuestion } from "./submissionForm";

export type SaveState = "idle" | "saving" | "saved" | "failed";

interface AnswerFieldProps {
  question: FormQuestion;
  state: SaveState;
  error?: string;
  disabled?: boolean;
  // Called when the answer is finished, not on each keystroke
  onAnswered: (values: string[]) => void;
  // Called when the submitter says the quotes behind a suggestion do not support it, or takes that back
  onRejectEvidence?: (rejected: boolean) => void;
}

// What the field's save is doing. A save can be refused, such as when the request was submitted in
// another tab.
function SaveStatus({ state, error }: { state: SaveState; error?: string }) {
  if (state === "saving") {
    return <CircularProgress size={16} aria-label="Saving" />;
  }
  if (state === "saved") {
    return <Typography variant="caption">Saved</Typography>;
  }
  if (state === "failed") {
    // The only report that an answer was refused. The text carries it, since an SvgIcon is
    // aria-hidden. The wrapper takes focus, so that a keyboard reaches the reason in the tooltip.
    return (
      <Tooltip title={error ?? "This answer was not saved"}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }} tabIndex={0}>
          <ErrorOutlinedIcon color="error" fontSize="small" />
          <Typography variant="caption" color="error">Not saved</Typography>
        </Box>
      </Tooltip>
    );
  }
  return null;
}

// One question and its answer. The input is drawn by the registered answer component that fits the
// question best. This keeps what every kind of answer shares: following the saved answer, noticing a
// change, and reporting what the save does.
function AnswerField({ question, state, error, disabled, onAnswered, onRejectEvidence }: AnswerFieldProps) {
  const ready = useAnswerComponents();
  const { provenance } = question;
  // With nothing answered yet, the field starts out showing the suggestion. It stays a suggestion until
  // the submitter confirms or edits it: a reading never writes the answer itself.
  const shownFirst = provenance && isPrefilled(provenance, question.value) ? provenance.offered : question.value;
  const [ draft, setDraft ] = useState(shownFirst);
  const [ focused, setFocused ] = useState(false);
  // Follows the saved answer, which changes each time the editor reads the form again. It is adjusted
  // during render, which is how React follows a prop without an effect.
  //
  // Compared by content, since each read returns fresh arrays. Compared by identity, every save would
  // reset every field, including one somebody is typing in.
  // Joined on a character an answer cannot contain, so that ["a", "b"] and ["a b"] stay distinct.
  const answered = shownFirst.join("\u0000");
  const [ seen, setSeen ] = useState(answered);
  // Held back while the field has focus. Otherwise a submitter who empties a field and types again
  // would lose the new text when the read after the clearing lands. `seen` is left alone too, so the
  // new value is applied on the first render after they leave.
  if (seen !== answered && !focused) {
    setSeen(answered);
    setDraft(shownFirst);
  }

  // Leaving a field that still shows the suggestion saves nothing, so a suggestion is never taken
  // without the submitter acting on it
  const changed = (values: string[]) =>
    !isSameValues(values, shownFirst) && !(values.length === 0 && question.value.length === 0);
  const submit = (values: string[]) => {
    setDraft(values);
    if (changed(values)) {
      onAnswered(values);
    }
  };

  if (!ready) {
    // Not the "cannot be answered here" message below. Until the components have loaded, none is
    // registered, and the question may yet have one.
    return (
      <Box>
        <QuestionText question={question} labelOnly />
        <CircularProgress size={16} aria-label="Loading the answer field" />
      </Box>
    );
  }

  const Answer = getAnswerComponent(question);
  if (!Answer) {
    // Shown rather than skipped, so that the form does not look complete when it is not
    return (
      <Box>
        <QuestionText question={question} labelOnly />
        <Typography variant="placeholder">
          {`This question asks for ${question.dataType}, which cannot be answered here.`}
        </Typography>
      </Box>
    );
  }

  const status = provenance ? getStatus(provenance, draft, question.value) : undefined;
  const newer = getNewerOffer(provenance, question.value);
  const take = (values: string[]) => {
    setDraft(values);
    onAnswered(values);
  };

  return (
    <Box
      sx={{
        // The answer, then where its suggestion came from; one column on a phone
        display: "grid",
        gridTemplateColumns: provenance
          ? { xs: "minmax(0, 1fr)", md: "minmax(0, 3fr) minmax(0, 2fr)" }
          : "minmax(0, 1fr)",
        columnGap: 2,
        rowGap: 1,
        alignItems: "start",
      }}
      onFocusCapture={() => setFocused(true)}
      onBlurCapture={() => setFocused(false)}
    >
      <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1, minWidth: 0 }}>
        {/* react-hooks/static-components refuses <Answer/> for a component chosen during render */}
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          {createElement(Answer, {
            question,
            values: draft,
            disabled: Boolean(disabled),
            onChange: setDraft,
            onAnswered: submit,
            suggested: status === "suggested",
            corner: newer
              ? <NewerReadingSwitch question={question} values={newer} disabled={disabled} onAccept={take} />
              : undefined,
            aside: status
              ? <ConfirmAnswer status={status} disabled={disabled} onConfirm={() => take(draft)} />
              : undefined,
          })}
        </Box>
        {/* Lined up with the input, under the question text */}
        <Box sx={{ pt: 4, minWidth: 16 }}><SaveStatus state={state} error={error} /></Box>
      </Box>
      {provenance && status && (
        <AnswerProvenance
          provenance={provenance}
          status={status}
          disabled={disabled}
          onRejectEvidence={rejected => onRejectEvidence?.(rejected)}
        />
      )}
    </Box>
  );
}

export default AnswerField;
