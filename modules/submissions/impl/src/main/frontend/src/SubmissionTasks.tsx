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

import SendOutlinedIcon from "@mui/icons-material/SendOutlined";
import { Button, Stack } from "@mui/material";

import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { offers, sendEvent } from "@iap/frontend-commons/workflowEvents";

import { COMPLETE, type SubmissionTask } from "./taskModel";
import { useSubmissionTasks } from "./useSubmissionTasks";

// What a submission is waiting for, offered as the controls that answer it.
//
// The submit button is one of these rather than a thing of its own, because submitting is not a
// special act: it is a step of the submission's own workflow, completed the same way an approver
// completes theirs. What the button says is the task's own label, so a process that calls this
// step something else says so here without a line of code changing.
//
// Only what the reader may do is offered, as the server says in `@events`: a task that is not
// theirs is not shown at all, since what a reviewer may do with a request is not the submitter's
// business, and a control they can see and not press says it just as plainly. The engine still
// decides the act itself. And only tasks with nothing to decide: an approval needs somewhere to
// say why, which belongs with the review screen rather than in two more buttons here.
function SubmissionTasks({ path, onCompleted }: { path: string; onCompleted?: () => void }) {
  const { tasks, reload } = useSubmissionTasks(path);
  const doFetch = useAuthenticatedFetch();
  const notify = useNotice();
  const [ busy, setBusy ] = useState(false);

  const complete = (task: SubmissionTask) => {
    setBusy(true);
    sendEvent(doFetch, task.path, COMPLETE)
      .then(() => reload())
      .then(() => onCompleted?.())
      // The engine's own words: it is the definition that refused, and only it knows why
      .catch((error: unknown) => notify({ title: `${task.label} did not go through`, message: messageOf(error) }))
      .finally(() => setBusy(false));
  };

  const offered = tasks.filter(task => offers(task, COMPLETE) && task.outcomeOptions.length === 0);
  if (offered.length === 0) {
    return null;
  }
  return (
    <Stack direction="row" spacing={1}>
      { offered.map(task => (
        <Button
          key={task.path}
          variant="contained"
          startIcon={<SendOutlinedIcon />}
          disabled={busy}
          onClick={() => complete(task)}
        >
          {task.label}
        </Button>
      )) }
    </Stack>
  );
}

export default SubmissionTasks;
