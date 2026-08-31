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

import { createElement, useCallback, useEffect, useRef, useState } from "react";

import { Alert, CircularProgress, Divider, Paper, Stack, Typography } from "@mui/material";

import { getRequirementComponent, type FieldState } from "./requirementComponents";
import { registerBuiltinRequirementComponents } from "./requirements";
import {
  type FormQuestion,
  type FormRequirement,
  type SubmissionForm,
  fetchForm,
  saveAnswer,
} from "./submissionForm";

registerBuiltinRequirementComponents();

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// One requirement. Its heading is the same whatever kind it is; what fills it is whichever component
// claimed the kind, so a kind declared by another module is drawn without this file naming it. A kind
// nothing claims is still shown, because the schema asks for it and saying nothing about it would
// read as the form being broken rather than as the step being somebody else's.
function Requirement({ path, requirement, disabled, states, onAnswered, onChanged }: {
  path: string;
  requirement: FormRequirement;
  disabled: boolean;
  states: Record<string, FieldState | undefined>;
  onAnswered: (question: FormQuestion, values: string[]) => void;
  onChanged: () => void;
}) {
  const Body = getRequirementComponent(requirement);
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography variant="h6">{requirement.label || requirement.name}</Typography>
      { requirement.description && (
        <Typography variant="body2" color="text.secondary">{requirement.description}</Typography>
      ) }
      <Divider sx={{ my: 2 }} />
      { Body
        // Built through createElement rather than as <Body/>: which component this is depends on the
        // requirement, and JSX on a value looks to the compiler like a component being defined here on
        // every render. The registry hands back the same function each time, so nothing remounts.
        ? createElement(Body, { path, requirement, disabled, states, onAnswered, onChanged })
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
          // The form again, because what it asks can change with what a requirement just did: one
          // that is now answered, and a request that is no longer incomplete
          onChanged={() => {
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
