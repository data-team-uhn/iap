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

import { useCallback, useEffect, useState } from "react";

import SendIcon from "@mui/icons-material/Send";
import { Alert, Button, Stack, Tooltip } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { type SubmissionTask, completeTask, fetchOpenTasks } from "./submissionTasks";

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// What a submission is waiting for, offered as the controls that answer it.
//
// The submit button is one of these rather than a thing of its own, because submitting is not a
// special act: it is a step of the submission's own workflow, completed the same way an approver
// completes theirs. What the button says is the task's own label, so a process that calls this
// step something else says so here without a line of code changing.
//
// Only tasks with nothing to decide are offered yet. A task that carries decisions — an approval,
// with its approve and reject — needs somewhere to say *why*, and that belongs with the task list
// and the review screen rather than being smuggled in as two more buttons here.
//
// Offered to whoever is looking, like the Edit control beside it: whether this person may actually
// complete the task is the definition's answer, given by the engine when they press it, and the
// refusal appears here in the engine's own words. A workflow's performers can name groups and
// group membership, which a page cannot evaluate — so a page that tried to decide this for itself
// would be guessing, and would sometimes hide a control from somebody entitled to use it.
//
// `blockedReason` is the one thing a page may decide, and it is not about *who* may act: it is about
// whether the request is ready to be sent at all, which the save workflow has already worked out and
// recorded on the submission. Given rather than read here so that this control keeps fetching only
// what a task list needs — the caller already holds the submission.
function SubmissionTasks(
  { path, blockedReason, onCompleted }:
  { path: string; blockedReason?: string; onCompleted?: () => void },
) {
  const authenticatedFetch = useAuthenticatedFetch();
  const [ tasks, setTasks ] = useState<SubmissionTask[]>([]);
  const [ error, setError ] = useState<string>();
  const [ busy, setBusy ] = useState(false);

  // Failing to read this is quiet, and quiet in the same way wherever it happens: not being able to
  // tell what a request is waiting for means offering nothing, which is what an empty list already
  // says. The error below is for a refusal — the engine saying no — which is a different thing, and
  // the only one worth putting in somebody's way.
  const load = useCallback(() => fetchOpenTasks(path).then(setTasks, () => setTasks([])), [ path ]);

  useEffect(() => {
    void load();
  }, [ load ]);

  const complete = (task: SubmissionTask) => {
    setBusy(true);
    setError(undefined);
    completeTask(authenticatedFetch, task)
      .then(() => load())
      .then(() => onCompleted?.())
      .catch((e: unknown) => setError(message(e)))
      .finally(() => setBusy(false));
  };

  const offered = tasks.filter(task => task.outcomeOptions.length === 0);
  if (offered.length === 0 && !error) {
    return null;
  }

  return (
    <Stack spacing={1}>
      { offered.map(task => (
        // Wrapped, because a disabled button fires no events and so shows no tooltip of its own: the
        // reason has to hang on something that is still listening
        <Tooltip key={task.path} title={blockedReason ?? ""}>
          <span>
            <Button
              variant="contained"
              startIcon={<SendIcon />}
              disabled={busy || blockedReason != undefined}
              onClick={() => complete(task)}
            >
              {task.label}
            </Button>
          </span>
        </Tooltip>
      )) }
      { error && <Alert severity="error" onClose={() => setError(undefined)}>{error}</Alert> }
    </Stack>
  );
}

export default SubmissionTasks;
