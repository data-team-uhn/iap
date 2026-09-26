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

import { Alert, Box, Button, CircularProgress, Divider, Paper, Stack, Tooltip, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure } from "@iap/frontend-commons/requestFailure";

import AnswerField, { type SaveState } from "./AnswerField";
import DocumentUpload from "./DocumentUpload";
import { completeTask, fetchOpenTasks, type SubmissionTask } from "./openTasks";
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
function Requirement({ path, requirement, disabled, states, onAnswered, onReviewed, onAttached,
  blockedReason, onTaskCompleted, refreshToken, offerStep }: {
  path: string;
  requirement: FormRequirement;
  disabled: boolean;
  states: Record<string, FieldState | undefined>;
  onAnswered: (question: FormQuestion, values: string[]) => void;
  onReviewed: (question: FormQuestion, verdict: ReviewVerdict) => void;
  onAttached: () => void;
  blockedReason?: string;
  onTaskCompleted?: () => void;
  refreshToken?: number;
  // A document step that starts the reading is the Next button's job, so it is not also offered here.
  offerStep: boolean;
}) {
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography variant="h6">{requirement.label || requirement.name}</Typography>
      { requirement.description && (
        <Typography variant="description">{requirement.description}</Typography>
      ) }
      <Divider sx={{ my: 2 }} />
      { requirement.type === FORM_REQUIREMENT && requirement.items
        ? (
          <Items items={requirement.items} disabled={disabled} states={states} onAnswered={onAnswered}
            onReviewed={onReviewed} />
        )
        : requirement.type === DOCUMENT_REQUIREMENT
          ? <DocumentUpload
            path={path}
            requirement={requirement}
            disabled={disabled}
            onAttached={onAttached}
          />
          : (
            <Typography variant="description">
              This part of the request is somebody else&apos;s step, and cannot be completed here.
            </Typography>
          ) }
      {/* The step this requirement is about, when its definition named one: pressing it acts on what is
          directly above, rather than on a button at the top of the page that says nothing about which
          part of the form it belongs to. Renders nothing when no task named this requirement. */}
      { offerStep
        ? (
          <SubmissionTasks
            path={path}
            requirement={requirement.name}
            // The page's reason is about sending the request, and a request with open questions always has
            // one. A step under a form section is how those questions get answered, so it must not wait.
            blockedReason={requirement.type === FORM_REQUIREMENT ? undefined : blockedReason}
            onCompleted={onTaskCompleted}
            refreshToken={refreshToken}
          />
        )
        : null }
    </Paper>
  );
}

// What this page of the editor shows. A schema that reads its documents is two pages: the upload and
// the questions answered by hand, then only the sections the model fills in. Anything else is one page.
function shown(form: SubmissionForm, page: 1 | 2): FormRequirement[] {
  const asked = form.requirements.filter(requirement => requirement.type !== APPROVAL_REQUIREMENT);
  if (!form.readsDocuments) {
    return asked;
  }
  return page === 2
    ? asked.filter(requirement => requirement.extracted === true)
    : asked.filter(requirement => requirement.extracted !== true);
}

// The step that sends an uploaded document to be read, when one is waiting. It is the task whose
// requirement names a document, which used to be a button under that upload.
function readingTask(tasks: SubmissionTask[], requirements: FormRequirement[]): SubmissionTask | undefined {
  const documents = new Set(
    requirements.filter(requirement => requirement.type === DOCUMENT_REQUIREMENT).map(requirement => requirement.name),
  );
  return tasks.find(task => task.requirement !== undefined && documents.has(task.requirement));
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

// Why Next cannot start the reading yet. The document it would send has to be attached, and every
// required question on this page has to be answered. Questions the model fills in are on the next
// page, so they do not hold this one back.
function whyNextWaits(task: SubmissionTask | undefined, requirements: FormRequirement[]): string | undefined {
  if (task?.requirement !== undefined) {
    const document = requirements.find(requirement => requirement.name === task.requirement);
    if (document?.required === true && (document.attached ?? []).length === 0) {
      return `Attach the ${document.label || document.name} before going on.`;
    }
  }
  const open = requirements
    .filter(requirement => requirement.type === FORM_REQUIREMENT && requirement.extracted !== true)
    .map(requirement => unanswered(requirement.items))
    .find(question => question !== undefined);
  return open ? "Answer every required question before going on." : undefined;
}

// Starts the reading, then opens the page of answers the model fills in. When the reading was
// already started, it only turns the page.
function NextPage({ path, requirements, refreshToken, onOpened }: {
  path: string;
  requirements: FormRequirement[];
  refreshToken?: number;
  onOpened: () => void;
}) {
  const authenticatedFetch = useAuthenticatedFetch();
  const [ task, setTask ] = useState<SubmissionTask>();
  const [ error, setError ] = useState<string>();
  const [ busy, setBusy ] = useState(false);

  const load = useCallback(() => fetchOpenTasks(path).then(
    tasks => setTask(readingTask(tasks, requirements)),
    () => setTask(undefined),
  ), [ path, requirements ]);

  useEffect(() => {
    void load();
  }, [ load, refreshToken ]);

  const waiting = whyNextWaits(task, requirements);

  const go = () => {
    if (waiting) {
      return;
    }
    if (!task) {
      onOpened();
      return;
    }
    setBusy(true);
    setError(undefined);
    completeTask(authenticatedFetch, task)
      .then(() => onOpened())
      .catch((e: unknown) => setError(describeRequestFailure(e)))
      .finally(() => setBusy(false));
  };

  return (
    <Stack spacing={1} sx={{ alignItems: "flex-start" }}>
      <Tooltip title={waiting ?? ""}>
        <span>
          <Button variant="contained" disabled={busy || waiting !== undefined} onClick={go}>
            Next
          </Button>
        </span>
      </Tooltip>
      { error ? <Alert severity="error" onClose={() => setError(undefined)}>{error}</Alert> : null }
    </Stack>
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
  // 1 is the upload and the questions answered by hand. 2 is the answers read out of the document.
  const [ page, setPage ] = useState<1 | 2>(1);
  // Which read is the current one. Answers finished in quick succession are saved in the order they
  // were given, but their reads can land out of order, and an older form would put back what was
  // just replaced.
  const latest = useRef(0);
  const doFetch = useAuthenticatedFetch();

  const reload = useCallback((token: number) => fetchForm(path, doFetch).then(next => {
    if (token === latest.current) {
      setForm(next);
      setError(undefined);
    }
  }), [ path, doFetch ]);

  useEffect(() => {
    const token = latest.current;
    reload(token).catch((e: unknown) => setError(describeRequestFailure(e)));
  }, [ reload, refreshToken ]);

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
        { ...current, [question.path]: { state: "failed", error: describeRequestFailure(e) } })));
  }, [ path, reload, onChanged ]);

  // Recording a verdict changes nothing the submitter typed, so it does not touch the per-field save
  // state. It does reload, because the server decides how the answer then reads back.
  const reviewed = useCallback((question: FormQuestion, verdict: ReviewVerdict) => {
    const token = latest.current + 1;
    latest.current = token;
    reviewExtraction(path, question.path, verdict)
      .then(() => reload(token))
      .catch((e: unknown) => setStates(current => (
        { ...current, [question.path]: { state: "failed", error: describeRequestFailure(e) } })));
  }, [ path, reload ]);

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
      {/* Approvals are left out: the submitter cannot act on them, and a reviewer's step shown as a
          form section reads as something still to fill in. The read-only page lists where they stand. */}
      { shown(form, page).map(requirement => (
        <Requirement
          key={requirement.name}
          path={path}
          requirement={requirement}
          disabled={!form.editable}
          states={states}
          onAnswered={answered}
          onReviewed={reviewed}
          blockedReason={blockedReason}
          onTaskCompleted={onTaskCompleted}
          refreshToken={refreshToken}
          // The reading is started from Next, below, rather than from a button under the upload.
          offerStep={!(form.readsDocuments && requirement.type === DOCUMENT_REQUIREMENT)}
          // The form again, because what it asks can change with what was just attached: a
          // requirement that is now answered, and a request that is no longer incomplete
          onAttached={() => {
            const token = latest.current + 1;
            latest.current = token;
            onChanged?.();
            reload(token).catch((e: unknown) => setError(describeRequestFailure(e)));
          }}
        />
      )) }
      { form.readsDocuments && page === 1
        ? (
          <NextPage
            path={path}
            requirements={form.requirements}
            refreshToken={refreshToken}
            onOpened={() => {
              setPage(2);
              onTaskCompleted?.();
            }}
          />
        )
        : null }
      { form.readsDocuments && page === 2 && shown(form, 2).length === 0
        ? <Typography variant="placeholder">The answers read from the document will appear here.</Typography>
        : null }
      { form.readsDocuments && page === 2
        ? <Button onClick={() => setPage(1)}>Back</Button>
        : null }
      { form.requirements.length === 0 && (
        <Typography variant="placeholder">This request asks nothing yet.</Typography>
      ) }
    </Stack>
  );
}

export default SubmissionEditor;
