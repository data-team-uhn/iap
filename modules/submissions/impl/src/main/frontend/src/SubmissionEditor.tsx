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

// The editor of a submission. It saves each answer once it is finished, and then reads the form
// again. Which questions apply depends on the answers, and only the server decides it. A question
// that stops applying is absent from the next read.
//
// `onSaving` hears of each save as it is asked for, for whatever must wait until the answers are
// stored. `onChanged` hears that a save or an attachment went through, so that the page can read
// the submission again.
function SubmissionEditor({ path, onSaving = ignoreSave, onChanged }: {
  path: string;
  onSaving?: (save: Promise<unknown>) => void;
  onChanged?: () => void;
}) {
  const [ form, setForm ] = useState<SubmissionForm>();
  const [ error, setError ] = useState<string>();
  // The save state of each field, by question path; absent until the field is first saved
  const [ states, setStates ] = useState<Record<string, FieldState | undefined>>({});
  // The token of the newest read of the form. Answers finished in quick succession each read the form
  // again, and the reads can land out of order. Only the newest is applied, or an older form would put
  // back what a newer one replaced.
  const latestFormRead = useRef(0);
  // The newest save. Each save waits for the one before it to settle, whichever way it went, so that
  // answers reach the server one at a time and in order. Two saves of one submission in flight
  // together would be rival commits, and the engine refuses the one that loses.
  const previousSave = useRef<Promise<unknown>>(Promise.resolve());
  const doFetch = useAuthenticatedFetch();
  // A requirement drawn by another module's component waits for that component to load. Until then
  // it would show as something that cannot be completed here.
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
    const store = () => saveAnswer(doFetch, path, question.path, values);
    const save = previousSave.current.then(store, store);
    previousSave.current = save;
    onSaving(save);
    save
      // The field's own outcome, applied even when a later answer has overtaken this one. Handled
      // here rather than in the trailing catch, which is for the read alone.
      .then(
        () => {
          setStates(current => ({ ...current, [question.path]: { state: "saved" } }));
          onChanged?.();
        },
        (e: unknown) => setStates(current => (
          { ...current, [question.path]: { state: "failed", error: message(e) } })),
      )
      // Read again whichever way the save went. This save holds the newest token, so no other read
      // would be applied.
      .then(() => reload(token))
      // A failed read is reported for the form, not for the field
      .catch((e: unknown) => setError(message(e)));
  }, [ doFetch, path, reload, onSaving, onChanged ]);

  // Reads the form again after a requirement's component changed the submission, such as by
  // attaching a file
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
