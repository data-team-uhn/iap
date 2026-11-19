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

import { type ReactNode, createElement, useCallback, useEffect, useRef, useState } from "react";

import { Alert, CircularProgress, Stack, Typography } from "@mui/material";

import ErrorBoundary from "@iap/frontend-commons/components/ErrorBoundary";
import Panel from "@iap/frontend-commons/components/Panel";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import type { Enqueue } from "@iap/frontend-commons/useWorkQueue";
import { sendEvent } from "@iap/frontend-commons/workflowEvents";

import { areDocumentsSent, getStartingStep, getStep } from "./intake";
import IntakeSteps from "./IntakeSteps";
import ProposalDialog from "./ProposalDialog";
import {
  getRequirementComponent,
  type FieldState,
  type RequirementComponentProps,
} from "./requirementComponents";
import { registerBuiltinRequirementComponents, useRequirementComponents } from "./requirements";
import { getChoice, getGeneratedVersion } from "./routeModel";
import RouteView from "./RouteView";
import {
  type FormQuestion,
  type Requirement,
  type SubmissionForm,
  fetchForm,
  isDocumentRequirement,
  isFormRequirement,
  reviewExtraction,
  saveAnswer,
  toTitle,
} from "./submissionForm";
import { COMPLETE_EVENT, type SubmissionTask } from "./taskModel";
import { type SubmissionSteps } from "./useSubmissionTasks";

registerBuiltinRequirementComponents();

const CANNOT_COMPLETE_HERE = (
  <Typography variant="placeholder">This part of the request cannot be completed here yet.</Typography>
);

// What a requirement asks for, drawn by the component registered for its kind. A kind with no
// component says it cannot be completed here yet. While a document is being read, the questions are
// not shown: the reading is adding suggestions to them, and an answer saved at the same time could
// clash with it.
function RequirementBody({ reading, ...props }: RequirementComponentProps & { reading: boolean }) {
  if (reading && isFormRequirement(props.requirement)) {
    return (
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <CircularProgress size={16} />
        <Typography variant="placeholder">The answers are shown once the document has been read.</Typography>
      </Stack>
    );
  }
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
// Each save, of an answer or of a file a requirement's component attaches, goes through `queueSave`,
// the queue of the work on this submission. It starts once the work before it has settled. Two saves
// of one submission in flight together would be rival commits, and the engine refuses the one that
// loses.
//
// `onChanged` hears that a save or an attachment went through, so that the page can read the
// submission again. `reading` says a document is being read for answers; until the reading ends, the
// questions are hidden and every requirement is turned off.
//
// `steps` are what the request's workflow waits for. When its first step asked for a form, that form
// picks the way the request goes: it stays on show, and the step the workflow waits for is shown under
// it. Changing its answer completes that step, and the workflow, which reads the answer again after each
// step, takes the other way. `sendControls` are the request's send buttons, offered with a document the
// platform wrote.
function SubmissionEditor({ path, queueSave, reading = false, steps, sendControls, onChanged }: {
  path: string;
  queueSave: Enqueue;
  reading?: boolean;
  steps?: SubmissionSteps;
  sendControls?: ReactNode;
  onChanged?: () => void;
}) {
  const [ form, setForm ] = useState<SubmissionForm>();
  const [ error, setError ] = useState<string>();
  // The step the submitter moved to, absent until they move: the form then says where to start
  const [ step, setStep ] = useState<number>();
  // The workflow step last completed here. Until the next one is read, the editor waits for it.
  const [ completedStep, setCompletedStep ] = useState<string>();
  // Whether to show the document the platform wrote, which it does when a form step is completed
  const [ proposalShown, setProposalShown ] = useState(false);
  const [ stepFailure, setStepFailure ] = useState<string>();
  // The save state of each field, by question path; absent until the field is first saved
  const [ states, setStates ] = useState<Record<string, FieldState | undefined>>({});
  // The token of the newest read of the form. Answers finished in quick succession each read the form
  // again, and the reads can land out of order. Only the newest is applied, or an older form would put
  // back what a newer one replaced.
  const latestFormRead = useRef(0);
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
    reload(token).catch((e: unknown) => setError(messageOf(e)));
  }, [ reload ]);

  const answer = useCallback((question: FormQuestion, values: string[]): Promise<unknown> => {
    const token = latestFormRead.current + 1;
    latestFormRead.current = token;
    setStates(current => ({ ...current, [question.path]: { state: "saving" } }));
    const save = queueSave(() => saveAnswer(doFetch, path, question.path, values));
    save
      // The field's own outcome, applied even when a later answer has overtaken this one. Handled
      // here rather than in the trailing catch, which is for the read alone.
      .then(
        () => {
          setStates(current => ({ ...current, [question.path]: { state: "saved" } }));
          onChanged?.();
        },
        (e: unknown) => setStates(current => (
          { ...current, [question.path]: { state: "failed", error: messageOf(e) } })),
      )
      // Read again whichever way the save went. This save holds the newest token, so no other read
      // would be applied.
      .then(() => reload(token))
      // A failed read is reported for the form, not for the field
      .catch((e: unknown) => setError(messageOf(e)));
    return save;
  }, [ doFetch, path, reload, queueSave, onChanged ]);

  const answered = useCallback((question: FormQuestion, values: string[]) => {
    void answer(question, values);
  }, [ answer ]);

  // A note about the quotes changes no answer, so only the form is read again to show it
  const rejectEvidence = useCallback((question: FormQuestion, rejected: boolean) => {
    const token = latestFormRead.current + 1;
    latestFormRead.current = token;
    queueSave(() => reviewExtraction(doFetch, path, question.path, rejected))
      .then(
        () => reload(token),
        (e: unknown) => setStates(current => (
          { ...current, [question.path]: { state: "failed", error: messageOf(e) } })),
      )
      .catch((e: unknown) => setError(messageOf(e)));
  }, [ doFetch, path, reload, queueSave ]);

  // Reads the form again after a requirement's component changed the submission, such as by
  // attaching a file
  const changed = useCallback(() => {
    const token = latestFormRead.current + 1;
    latestFormRead.current = token;
    onChanged?.();
    reload(token).catch((e: unknown) => setError(messageOf(e)));
  }, [ reload, onChanged ]);

  // Completes a workflow step after the saves queued before it, then reads the form again, since the
  // next step may have written something, such as a document from the answers
  const completeStep = useCallback((task: SubmissionTask) => queueSave(
    () => sendEvent(doFetch, task.path, COMPLETE_EVENT))
    .then(() => {
      setCompletedStep(task.path);
      const token = latestFormRead.current + 1;
      latestFormRead.current = token;
      onChanged?.();
      return reload(token);
    }), [ doFetch, reload, queueSave, onChanged ]);

  // Load the form again when a reading ends, to show what the reading added
  const wasReading = useRef(reading);
  useEffect(() => {
    if (wasReading.current && !reading) {
      const token = latestFormRead.current + 1;
      latestFormRead.current = token;
      reload(token).catch((e: unknown) => setError(messageOf(e)));
    }
    wasReading.current = reading;
  }, [ reading, reload ]);

  if (error) {
    return <Alert severity="error">{error}</Alert>;
  }
  if (!form || !componentsReady) {
    return <CircularProgress aria-label="Loading the request" />;
  }

  // A request whose documents a model reads is filled in by steps, while it can still be changed
  const inSteps = form.readsDocuments === true && form.editable;
  const shownStep = step ?? getStartingStep(form);
  const renderPanel = (requirement: Requirement, locked = false, onAnswer = answered) => (
    <Panel
      key={requirement.name}
      title={toTitle(requirement.label || requirement.name)}
      subtitle={isFormRequirement(requirement) && requirement.extracted
        ? [ requirement.description, "Filled in from your uploaded document. Check each answer." ]
          .filter(Boolean).join(" ")
        : requirement.description}
    >
      <ErrorBoundary fallback={CANNOT_COMPLETE_HERE}>
        <RequirementBody
          path={path}
          requirement={requirement}
          disabled={locked || !form.editable || reading
            || (inSteps && isDocumentRequirement(requirement) && areDocumentsSent(form))}
          reading={reading}
          states={states}
          onAnswered={onAnswer}
          onRejectEvidence={rejectEvidence}
          queueSave={queueSave}
          onChanged={changed}
        />
      </ErrorBoundary>
    </Panel>
  );
  // `sendUploads` is the upload step's Next, when a workflow step waits for the uploads
  const renderReadingSteps = (requirements: Requirement[], sendUploads?: () => Promise<void>) => (
    <IntakeSteps path={path} form={form} step={shownStep} reading={reading} onStep={setStep} onChanged={changed}
      sendUploads={sendUploads}>
      {requirements.filter(requirement => getStep(requirement) === shownStep)
        .map(requirement => renderPanel(requirement))}
    </IntakeSteps>
  );

  const choice = form.editable ? getChoice(form, steps?.opening) : undefined;
  const generated = getGeneratedVersion(form);

  return (
    <Stack spacing={2}>
      <Typography variant="h5">{form.title}</Typography>
      { !form.editable && (
        <Alert severity="info">
          This request can no longer be changed. It is shown as it was submitted.
        </Alert>
      ) }
      { stepFailure && <Alert severity="error" onClose={() => setStepFailure(undefined)}>{stepFailure}</Alert> }
      { choice && (
        <RouteView
          form={form}
          choice={choice}
          tasks={steps?.tasks ?? []}
          completedStep={completedStep}
          renderPanel={renderPanel}
          renderReadingSteps={renderReadingSteps}
          answer={answer}
          completeStep={completeStep}
          onFormCompleted={() => setProposalShown(true)}
          onSwitchFailed={setStepFailure}
        />
      ) }
      { !choice && (inSteps
        ? renderReadingSteps(form.requirements)
        : form.requirements.map(requirement => renderPanel(requirement))) }
      { proposalShown && generated && (
        <ProposalDialog document={generated} sendControls={sendControls} onClose={() => setProposalShown(false)} />
      ) }
      { form.requirements.length === 0 && (
        <Typography variant="placeholder">This request asks nothing yet.</Typography>
      ) }
    </Stack>
  );
}

export default SubmissionEditor;
