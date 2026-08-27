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

import { useCallback, useEffect, useRef, useState } from "react";

import { Alert, Box, CircularProgress, Divider, Paper, Stack, Typography } from "@mui/material";

import AnswerField, { type SaveState } from "./AnswerField";
import ApprovalState from "./ApprovalState";
import DocumentUpload from "./DocumentUpload";
import {
  APPROVAL_REQUIREMENT,
  DOCUMENT_REQUIREMENT,
  FORM_REQUIREMENT,
  type FormItem,
  type FormQuestion,
  type FormRequirement,
  type SubmissionForm,
  fetchForm,
  isQuestion,
  saveAnswer,
} from "./submissionForm";

interface FieldState {
  state: SaveState;
  error?: string;
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// The questions of a form or a section, with sections drawn as their own headed block.
function Items({ items, disabled, states, onAnswered }: {
  items: FormItem[];
  disabled: boolean;
  states: Record<string, FieldState | undefined>;
  onAnswered: (question: FormQuestion, values: string[]) => void;
}) {
  return (
    <Stack spacing={2}>
      { items.map(item => isQuestion(item)
        ? (
          <AnswerField
            key={item.path}
            question={item}
            disabled={disabled}
            state={states[item.path]?.state ?? "idle"}
            error={states[item.path]?.error}
            onAnswered={values => onAnswered(item, values)}
          />
        )
        : (
          <Box key={item.name}>
            <Typography variant="subtitle1">{item.label || item.name}</Typography>
            { item.description && (
              <Typography variant="body2" color="text.secondary">{item.description}</Typography>
            ) }
            <Box sx={{ pl: 2, pt: 1 }}>
              <Items items={item.items} disabled={disabled} states={states} onAnswered={onAnswered} />
            </Box>
          </Box>
        )) }
    </Stack>
  );
}

// One requirement. A requirement that holds no questions is still shown, and where it can be
// answered it is answered here: a document is uploaded, and an approval says where it stands
// because it is somebody else who grants it.
function Requirement({ path, requirement, disabled, states, onAnswered, onAttached }: {
  path: string;
  requirement: FormRequirement;
  disabled: boolean;
  states: Record<string, FieldState | undefined>;
  onAnswered: (question: FormQuestion, values: string[]) => void;
  onAttached: () => void;
}) {
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography variant="h6">{requirement.label || requirement.name}</Typography>
      { requirement.description && (
        <Typography variant="body2" color="text.secondary">{requirement.description}</Typography>
      ) }
      <Divider sx={{ my: 2 }} />
      { requirement.type === FORM_REQUIREMENT && requirement.items
        ? <Items items={requirement.items} disabled={disabled} states={states} onAnswered={onAnswered} />
        : requirement.type === DOCUMENT_REQUIREMENT
          ? <DocumentUpload
            path={path}
            requirement={requirement}
            disabled={disabled}
            onAttached={onAttached}
          />
          : requirement.type === APPROVAL_REQUIREMENT
            ? <ApprovalState requirement={requirement} />
            : (
              <Typography variant="body2" color="text.secondary">
                This part of the request is somebody else&apos;s step, and cannot be completed here.
              </Typography>
            ) }
    </Paper>
  );
}

// Filling a submission in.
//
// There is no Save button: an answer is saved when it is *finished* — a field left, a box ticked —
// and the form is then read again. That is what keeps the questions on screen correct, because
// which of them apply depends on the answers, and the server is the only thing that decides it.
// Nothing here evaluates a condition; a question that stops applying simply stops being sent.
//
// `onChanged` says that the request itself has changed, which is more than the form knowing it: what
// the request is still missing is recorded on the submission, and the control offering to *send* it
// reads that. Without this, answering the last question or attaching the last document leaves that
// control refusing a request that is now complete, until something else re-reads the page.
function SubmissionEditor({ path, onChanged }: { path: string; onChanged?: () => void }) {
  const [ form, setForm ] = useState<SubmissionForm>();
  const [ error, setError ] = useState<string>();
  // Absent until a field has been saved at least once, so reading one may find nothing
  const [ states, setStates ] = useState<Record<string, FieldState | undefined>>({});
  // Which read is the current one. Answers finished in quick succession are saved in the order they
  // were given, but their reads can land out of order, and an older form would put back what was
  // just replaced.
  const latest = useRef(0);

  const reload = useCallback((token: number) => fetchForm(path).then(next => {
    if (token === latest.current) {
      setForm(next);
      setError(undefined);
    }
  }), [ path ]);

  useEffect(() => {
    const token = latest.current;
    reload(token).catch((e: unknown) => setError(message(e)));
  }, [ reload ]);

  const answered = useCallback((question: FormQuestion, values: string[]) => {
    const token = latest.current + 1;
    latest.current = token;
    setStates(current => ({ ...current, [question.path]: { state: "saving" } }));
    saveAnswer(path, question.path, values)
      .then(() => {
        // The field's own outcome, whether or not a later answer has overtaken this one: a save that
        // succeeded should not be reported as still saving because something else happened after it
        setStates(current => ({ ...current, [question.path]: { state: "saved" } }));
        onChanged?.();
        return reload(token);
      })
      .catch((e: unknown) => setStates(current => (
        { ...current, [question.path]: { state: "failed", error: message(e) } })));
  }, [ path, reload, onChanged ]);

  if (error) {
    return <Alert severity="error">{error}</Alert>;
  }
  if (!form) {
    return <CircularProgress aria-label="Loading the request" />;
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h5">{form.title}</Typography>
      { !form.editable && (
        <Alert severity="info">
          This request can no longer be changed. It is shown as it was submitted.
        </Alert>
      ) }
      { form.requirements.map(requirement => (
        <Requirement
          key={requirement.name}
          path={path}
          requirement={requirement}
          disabled={!form.editable}
          states={states}
          onAnswered={answered}
          // The form again, because what it asks can change with what was just attached: a
          // requirement that is now answered, and a request that is no longer incomplete
          onAttached={() => {
            const token = latest.current + 1;
            latest.current = token;
            onChanged?.();
            reload(token).catch((e: unknown) => setError(message(e)));
          }}
        />
      )) }
      { form.requirements.length === 0 && (
        <Typography color="text.secondary">This request asks nothing yet.</Typography>
      ) }
    </Stack>
  );
}

export default SubmissionEditor;
