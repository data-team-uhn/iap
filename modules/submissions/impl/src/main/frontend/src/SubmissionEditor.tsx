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

import { Alert, CircularProgress, Stack, Typography } from "@mui/material";

import ErrorBoundary from "@iap/frontend-commons/components/ErrorBoundary";
import Panel from "@iap/frontend-commons/components/Panel";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import {
  getRequirementComponent,
  type FieldState,
  type RequirementComponentProps,
} from "./requirementComponents";
import { registerBuiltinRequirementComponents, useRequirementComponents } from "./requirements";
import {
  type FormQuestion,
  type SubmissionForm,
  fetchForm,
  saveAnswer,
} from "./submissionForm";

registerBuiltinRequirementComponents();

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

const CANNOT_COMPLETE_HERE = (
  <Typography variant="placeholder">This part of the request cannot be completed here yet.</Typography>
);

function ignoreSave(): void {
  // Nothing on the page waits for the answers to be stored
}

// What a requirement asks for, drawn by the component registered for its kind. A kind with no
// component says it cannot be completed here yet.
function RequirementBody(props: RequirementComponentProps) {
  const Body = getRequirementComponent(props.requirement);
  if (!Body) {
    return CANNOT_COMPLETE_HERE;
  }
  // react-hooks/static-components refuses <Body/> for a component chosen during render
  return createElement(Body, props);
}

// Filling a submission in.
//
// There is no Save button. An answer is saved when it is finished, meaning a field left or a box
// ticked, and the form is then read again. That is what keeps the questions on screen correct, because
// which of them apply depends on the answers, and the server is the only thing that decides it.
// Nothing here evaluates a condition; a question that stops applying simply stops being sent.
// `onSaving` hears of each save as it starts, for whatever must wait until the answers are stored.
// `onChanged` hears that a save or an attachment went through, so a page reading the submission
// itself, such as what it is still missing, can read it again.
function SubmissionEditor({ path, onSaving = ignoreSave, onChanged }: {
  path: string;
  onSaving?: (save: Promise<unknown>) => void;
  onChanged?: () => void;
}) {
  const [ form, setForm ] = useState<SubmissionForm>();
  const [ error, setError ] = useState<string>();
  // Absent until a field has been saved at least once, so reading one may find nothing
  const [ states, setStates ] = useState<Record<string, FieldState | undefined>>({});
  // Which read is the current one. Answers finished in quick succession are saved in the order they
  // were given, but their reads can land out of order. An older form would put back what was just
  // replaced.
  const latestFormRead = useRef(0);
  const doFetch = useAuthenticatedFetch();
  // A requirement drawn by another module's component waits for that component to load, or it would
  // first show as something that cannot be completed here
  const componentsReady = useRequirementComponents();

  const reload = useCallback((token: number) => fetchForm(doFetch, path).then(next => {
    if (token === latestFormRead.current) {
      setForm(next);
      setError(undefined);
    }
  }), [ doFetch, path ]);

  useEffect(() => {
    const token = latestFormRead.current;
    reload(token).catch((e: unknown) => setError(message(e)));
  }, [ reload ]);

  const answered = useCallback((question: FormQuestion, values: string[]) => {
    const token = latestFormRead.current + 1;
    latestFormRead.current = token;
    setStates(current => ({ ...current, [question.path]: { state: "saving" } }));
    const save = saveAnswer(doFetch, path, question.path, values);
    onSaving(save);
    save
      // The field's own outcome, whether or not a later answer has overtaken this one. A save that
      // succeeded is not reported as still saving because something else happened after it. Settled
      // in this handler rather than in a trailing catch, so that only the read below reaches one
      .then(
        () => {
          setStates(current => ({ ...current, [question.path]: { state: "saved" } }));
          onChanged?.();
        },
        (e: unknown) => setStates(current => (
          { ...current, [question.path]: { state: "failed", error: message(e) } })),
      )
      // Read again whichever way the save went. This answer already holds the newest token, so
      // skipping the read after a refusal would leave the form waiting for one that never comes
      .then(() => reload(token))
      // A read that fails says nothing about the answer, which is why it is reported against the
      // form rather than against the field
      .catch((e: unknown) => setError(message(e)));
  }, [ doFetch, path, reload, onSaving, onChanged ]);

  // The form again: a requirement may now be answered, and the request no longer incomplete
  const changed = useCallback(() => {
    const token = latestFormRead.current + 1;
    latestFormRead.current = token;
    onChanged?.();
    reload(token).catch((e: unknown) => setError(message(e)));
  }, [ reload, onChanged ]);

  if (error) {
    return <Alert severity="error">{error}</Alert>;
  }
  if (!form || !componentsReady) {
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
        <Panel key={requirement.name} title={requirement.label || requirement.name} subtitle={requirement.description}>
          <ErrorBoundary fallback={CANNOT_COMPLETE_HERE}>
            <RequirementBody
              path={path}
              requirement={requirement}
              disabled={!form.editable}
              states={states}
              onAnswered={answered}
              onSaving={onSaving}
              onChanged={changed}
            />
          </ErrorBoundary>
        </Panel>
      )) }
      { form.requirements.length === 0 && (
        <Typography variant="placeholder">This request asks nothing yet.</Typography>
      ) }
    </Stack>
  );
}

export default SubmissionEditor;
