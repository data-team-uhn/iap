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

import { useState } from "react";

import ErrorOutlinedIcon from "@mui/icons-material/ErrorOutlined";
import { Box, Checkbox, CircularProgress, FormControlLabel, TextField, Tooltip, Typography } from "@mui/material";

import type { FormQuestion } from "./submissionForm";

// How each declared data type is typed in. `file` is deliberately absent: uploading through the
// workflow engine is its own mechanism and is not built yet, so such a question is shown as
// something that cannot be answered here rather than as a text box that would store a filename.
const INPUT_TYPES: Record<string, string> = {
  date: "date",
  long: "number",
  double: "number",
  text: "text",
};

export type SaveState = "idle" | "saving" | "saved" | "failed";

interface AnswerFieldProps {
  question: FormQuestion;
  state: SaveState;
  error?: string;
  disabled?: boolean;
  // Called when the answer is *complete* — a field left, a box ticked — rather than on every
  // keystroke. That is what makes saving as-you-go bearable, and it is also what keeps the saved
  // answers current enough for the server to re-decide which questions apply.
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
function AnswerField({ question, state, error, disabled, onAnswered }: AnswerFieldProps) {
  const [ draft, setDraft ] = useState(question.value);
  // The server is the authority on what the answer is: it re-reads the whole form after every save,
  // and an answer changed elsewhere should appear here. Adjusted while rendering, which is React's
  // own way of following a prop and avoids the extra pass an effect would cost.
  //
  // Compared by content rather than by identity, which matters: each read returns fresh arrays, so
  // an identity check would reset *every* field on *every* save — including one somebody is halfway
  // through typing in, whose keystrokes would vanish because a different field was saved.
  const answered = question.value.join("\u0000");
  const [ seen, setSeen ] = useState(answered);
  if (seen !== answered) {
    setSeen(answered);
    setDraft(question.value);
  }

  const label = question.text || question.name;
  const changed = (values: string[]) =>
    values.length !== question.value.length || values.some((value, index) => value !== question.value[index]);
  // Saving an answer that did not change would be a workflow event that means nothing
  const submit = (values: string[]) => {
    setDraft(values);
    if (changed(values)) {
      onAnswered(values);
    }
  };

  const status = <SaveStatus state={state} error={error} />;

  if (question.dataType === "boolean") {
    const ticked = draft[0] === "true";
    return (
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <FormControlLabel
          control={
            <Checkbox
              checked={ticked}
              disabled={disabled}
              // A tick is a finished answer the moment it happens; there is nothing to leave
              onChange={event => submit([ String(event.target.checked) ])}
            />
          }
          label={label}
        />
        {status}
      </Box>
    );
  }

  if (question.dataType === "file") {
    return (
      <Box>
        <Typography variant="subtitle2">{label}</Typography>
        <Typography variant="body2" color="text.secondary">
          Attaching a file is not available yet.
        </Typography>
      </Box>
    );
  }

  // Several values are typed one per line: a set of inputs that grow and shrink is a good deal more
  // machinery, and no question in use asks for more than one value yet
  const multiline = question.multiple;
  return (
    <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1 }}>
      <TextField
        label={label}
        type={multiline ? "text" : INPUT_TYPES[question.dataType] ?? "text"}
        required={question.required}
        disabled={disabled}
        multiline={multiline}
        minRows={multiline ? 2 : undefined}
        fullWidth
        value={multiline ? draft.join("\n") : draft[0] ?? ""}
        slotProps={{ inputLabel: { shrink: true } }}
        helperText={multiline ? `${question.description ?? ""} One per line.`.trim() : question.description}
        onChange={event => setDraft(
          multiline ? event.target.value.split("\n") : [ event.target.value ])}
        onBlur={event => submit(multiline
          ? event.target.value.split("\n").map(value => value.trim()).filter(Boolean)
          : [ event.target.value ].filter(Boolean))}
      />
      <Box sx={{ pt: 2 }}>{status}</Box>
    </Box>
  );
}

export default AnswerField;
