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

import { Alert, Box, Button, CircularProgress, Stack, Tooltip, Typography } from "@mui/material";

import Panel from "@iap/frontend-commons/components/Panel";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure } from "@iap/frontend-commons/requestFailure";

import AnswerField, { type SaveState } from "./AnswerField";
import DocumentUpload from "./DocumentUpload";
import {
  CLASSIFICATION_REQUIREMENT,
  type FormItem,
  type FormQuestion,
  type Requirement,
  type SubmissionForm,
  fetchForm,
  isApprovalRequirement,
  isDocumentRequirement,
  isFormRequirement,
  isQuestion,
  isRequired,
  reviewExtraction,
  saveAnswer,
} from "./submissionForm";
import SubmissionTasks from "./SubmissionTasks";

interface FieldState {
  state: SaveState;
  error?: string;
}

// What the submitter said about a pre-filled answer. Both keys are optional because the two verdicts
// are given separately: one settles the answer, the other reports a bad quote.
interface ReviewVerdict {
  confirmed?: boolean;
  evidenceRejected?: boolean;
}


// The questions of a form or a section, with sections drawn as their own headed block.
function Items({ items, disabled, states, onAnswered, onReviewed }: {
  items: FormItem[];
  disabled: boolean;
  states: Record<string, FieldState | undefined>;
  onAnswered: (question: FormQuestion, values: string[]) => void;
  onReviewed: (question: FormQuestion, verdict: ReviewVerdict) => void;
}) {
  return (
    <Stack spacing={3}>
      { items.map(item => isQuestion(item)
        ? (
          <AnswerField
            key={item.path}
            question={item}
            disabled={disabled}
            state={states[item.path]?.state ?? "idle"}
            error={states[item.path]?.error}
            onAnswered={values => onAnswered(item, values)}
            onAcceptSuggestion={() => onReviewed(item, { confirmed: true })}
            onRejectEvidence={rejected => onReviewed(item, { evidenceRejected: rejected })}
          />
        )
        : (
          <Box key={item.name}>
            <Typography variant="subtitle1">{item.label || item.name}</Typography>
            { item.description && (
              <Typography variant="description">{item.description}</Typography>
            ) }
            <Box sx={{ pl: 2, pt: 1 }}>
              <Items items={item.items} disabled={disabled} states={states} onAnswered={onAnswered}
                onReviewed={onReviewed} />
            </Box>
          </Box>
        )) }
    </Stack>
  );
}

// One requirement. A requirement that holds no questions is still shown, and where it can be
// answered it is answered here: a document is uploaded, and an approval says where it stands
// because it is somebody else who grants it.
function RequirementPanel({ path, requirement, disabled, reading, states, onAnswered, onReviewed, onAttached,
  blockedReason, onTaskCompleted, refreshToken }: {
  path: string;
  requirement: Requirement;
  disabled: boolean;
  reading: boolean;
  states: Record<string, FieldState | undefined>;
  onAnswered: (question: FormQuestion, values: string[]) => void;
  onReviewed: (question: FormQuestion, verdict: ReviewVerdict) => void;
  onAttached: () => void;
  blockedReason?: string;
  onTaskCompleted?: () => void;
  refreshToken?: number;
}) {
  return (
    <Panel title={requirement.label || requirement.name} subtitle={requirement.description}>
      { isFormRequirement(requirement)
        ? (
          <Items
            items={requirement.items}
            disabled={disabled}
            states={states}
            onAnswered={onAnswered}
            onReviewed={onReviewed}
          />
        )
        : isDocumentRequirement(requirement)
          ? (
            <DocumentUpload
              path={path}
              requirement={requirement}
              disabled={disabled}
              reading={reading}
              onAttached={onAttached}
            />
          )
          : (
            <Typography variant="placeholder">
              This part of the request cannot be completed here yet.
            </Typography>
          ) }
      {/* The step this requirement is about, when its definition named one. A step under a form is
          how those questions get answered, so it must not wait on the page's reason. */}
      <SubmissionTasks
        path={path}
        requirement={requirement.name}
        blockedReason={isFormRequirement(requirement) ? undefined : blockedReason}
        onCompleted={onTaskCompleted}
        refreshToken={refreshToken}
      />
    </Panel>
  );
}

// What this page of the editor shows. A schema that reads its documents is three pages: the upload and
// the questions answered by hand; the classification the model decides about the document; then only
// the other sections the model fills in, once that classification is settled. Anything else is one page.
function shown(form: SubmissionForm, page: 1 | 2 | 3): Requirement[] {
  const asked = form.requirements.filter(requirement => !isApprovalRequirement(requirement));
  if (!form.readsDocuments) {
    return asked;
  }
  if (page === 1) {
    return asked.filter(requirement => requirement.extracted !== true);
  }
  const extracted = asked.filter(requirement => requirement.extracted === true);
  return page === 2
    ? extracted.filter(requirement => requirement.type === CLASSIFICATION_REQUIREMENT)
    : extracted.filter(requirement => requirement.type !== CLASSIFICATION_REQUIREMENT);
}

// A required question on this page that still has nothing in it. Sections are walked so a question
// nested under a heading counts the same as one sitting directly in the requirement.
function unanswered(items: FormItem[] | undefined): FormQuestion | undefined {
  for (const item of items ?? []) {
    if (isQuestion(item)) {
      const filled = item.value.filter(value => value.trim() !== "");
      if (isRequired(item) && filled.length < item.minAnswers) {
        return item;
      }
    } else {
      const nested = unanswered(item.items);
      if (nested) {
        return nested;
      }
    }
  }
  return undefined;
}

// Why Next cannot turn the page yet: a required document on this page is not attached, or a required
// question on it is not answered. Uploading is what starts the reading, so Next only turns the page.
function whyNextWaits(requirements: Requirement[]): string | undefined {
  const missing = requirements.filter(isDocumentRequirement)
    .find(requirement => requirement.required && requirement.attached.length === 0);
  if (missing) {
    return `Attach the ${missing.label || missing.name} before going on.`;
  }
  const open = requirements
    .filter(isFormRequirement)
    .map(requirement => unanswered(requirement.items))
    .find(question => question !== undefined);
  return open ? "Answer every required question before going on." : undefined;
}

// Turns to the next page. Uploading is what starts the reading.
function NextButton({ requirements, onOpened }: {
  requirements: Requirement[];
  onOpened: () => void;
}) {
  const waiting = whyNextWaits(requirements);
  return (
    <Tooltip title={waiting ?? ""}>
      <span>
        <Button variant="contained" disabled={waiting !== undefined} onClick={onOpened}>
          Next
        </Button>
      </span>
    </Tooltip>
  );
}

// Filling a submission in.
//
// There is no Save button. An answer is saved when it is finished, meaning a field left or a box
// ticked, and the form is then read again. That is what keeps the questions on screen correct, because
// which of them apply depends on the answers, and the server is the only thing that decides it.
// Nothing here evaluates a condition; a question that stops applying simply stops being sent.
//
// `onChanged` says that the request itself has changed, which is more than the form knowing it: what
// the request is still missing is recorded on the submission, and the control offering to *send* it
// reads that. Without this, answering the last question or attaching the last document leaves that
// control refusing a request that is now complete, until something else re-reads the page.
function SubmissionEditor({ path, onChanged, blockedReason, onTaskCompleted, refreshToken }: {
  path: string;
  onChanged?: () => void;
  blockedReason?: string;
  onTaskCompleted?: () => void;
  // Bumped by the page when the submission changed behind the editor's back, such as answers landing
  // from the background reading. The editor cannot see that happen on its own.
  refreshToken?: number;
}) {
  const [ form, setForm ] = useState<SubmissionForm>();
  const [ error, setError ] = useState<string>();
  // Absent until a field has been saved at least once, so reading one may find nothing
  const [ states, setStates ] = useState<Record<string, FieldState | undefined>>({});
  // 1 is the upload and the questions answered by hand. 2 is the classification the model decides
  // about the document. 3 is the rest of the answers read out of it.
  const [ page, setPage ] = useState<1 | 2 | 3>(1);
  // Which read is the current one. Answers finished in quick succession are saved in the order they
  // were given, but their reads can land out of order. An older form would put back what was just
  // replaced.
  const latestFormRead = useRef(0);
  const doFetch = useAuthenticatedFetch();

  const reload = useCallback((token: number) => fetchForm(doFetch, path).then(next => {
    if (token === latestFormRead.current) {
      setForm(next);
      setError(undefined);
    }
  }), [ doFetch, path ]);

  useEffect(() => {
    const token = latestFormRead.current;
    reload(token).catch((e: unknown) => setError(describeRequestFailure(e)));
  }, [ reload, refreshToken ]);

  const answered = useCallback((question: FormQuestion, values: string[]) => {
    const token = latestFormRead.current + 1;
    latestFormRead.current = token;
    setStates(current => ({ ...current, [question.path]: { state: "saving" } }));
    saveAnswer(doFetch, path, question.path, values)
      // The field's own outcome, whether or not a later answer has overtaken this one. A save that
      // succeeded is not reported as still saving because something else happened after it. Settled
      // in this handler rather than in a trailing catch, so that only the read below reaches one
      .then(
        () => {
          setStates(current => ({ ...current, [question.path]: { state: "saved" } }));
          // What the request is still missing lives on the submission, which the send control reads
          onChanged?.();
        },
        (e: unknown) => setStates(current => (
          { ...current, [question.path]: { state: "failed", error: describeRequestFailure(e) } })),
      )
      // Read again whichever way the save went. This answer already holds the newest token, so
      // skipping the read after a refusal would leave the form waiting for one that never comes
      .then(() => reload(token))
      // A read that fails says nothing about the answer, which is why it is reported against the
      // form rather than against the field
      .catch((e: unknown) => setError(describeRequestFailure(e)));
  }, [ doFetch, path, reload, onChanged ]);

  // The form again, because what it asks can change with what was just attached: a requirement that
  // is now answered, and a request that is no longer incomplete
  const attached = useCallback(() => {
    const token = latestFormRead.current + 1;
    latestFormRead.current = token;
    onChanged?.();
    reload(token).catch((e: unknown) => setError(describeRequestFailure(e)));
  }, [ reload, onChanged ]);

  // Recording a verdict changes nothing the submitter typed, so it does not touch the per-field save
  // state. It does reload, because the server decides how the answer then reads back.
  const reviewed = useCallback((question: FormQuestion, verdict: ReviewVerdict) => {
    const token = latestFormRead.current + 1;
    latestFormRead.current = token;
    reviewExtraction(doFetch, path, question.path, verdict)
      .then(() => reload(token))
      // Distinct from a save failure: the answer itself is untouched here, only the review of it
      // was refused, and AnswerField must not show that as the answer having failed to save
      .catch((e: unknown) => setStates(current => (
        { ...current, [question.path]: { state: "reviewFailed", error: describeRequestFailure(e) } })));
  }, [ doFetch, path, reload ]);

  if (!form) {
    return error ? <Alert severity="error">{error}</Alert> : <CircularProgress aria-label="Loading the request" />;
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h5">{form.title}</Typography>
      { error ? <Alert severity="error" onClose={() => setError(undefined)}>{error}</Alert> : null }
      { !form.editable && (
        <Alert severity="info">
          This request can no longer be changed. It is shown as it was submitted.
        </Alert>
      ) }
      {/* Approvals are left out: the submitter cannot act on them, and a reviewer's step shown as a
          form section reads as something still to fill in. The read-only page lists where they stand. */}
      { shown(form, page).map(requirement => (
        <RequirementPanel
          key={requirement.name}
          path={path}
          requirement={requirement}
          disabled={!form.editable}
          reading={form.extraction?.status === "running"}
          states={states}
          onAnswered={answered}
          onReviewed={reviewed}
          blockedReason={blockedReason}
          onTaskCompleted={onTaskCompleted}
          refreshToken={refreshToken}
          onAttached={attached}
        />
      )) }
      { form.readsDocuments && page === 1 && (
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <NextButton requirements={shown(form, 1)} onOpened={() => setPage(2)} />
        </Box>
      ) }
      { form.readsDocuments && page === 2 && (
        <Stack direction="row" sx={{ justifyContent: "space-between" }}>
          <Button onClick={() => setPage(1)}>Back</Button>
          <NextButton requirements={shown(form, 2)} onOpened={() => setPage(3)} />
        </Stack>
      ) }
      { form.readsDocuments && page === 3 && shown(form, 3).length === 0 && (
        <Typography variant="placeholder">The answers read from the document will appear here.</Typography>
      ) }
      { form.readsDocuments && page === 3 && (
        <Box>
          <Button onClick={() => setPage(2)}>Back</Button>
        </Box>
      ) }
      { form.requirements.length === 0 && (
        <Typography variant="placeholder">This request asks nothing yet.</Typography>
      ) }
    </Stack>
  );
}

export default SubmissionEditor;
