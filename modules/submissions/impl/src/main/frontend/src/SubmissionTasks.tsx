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

import SendOutlinedIcon from "@mui/icons-material/SendOutlined";
import { Button, Stack } from "@mui/material";

import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";
import { offers, sendEvent } from "@iap/frontend-commons/workflowEvents";

import { COMPLETE, type SubmissionTask } from "./taskModel";
import { useSubmissionTasks } from "./useSubmissionTasks";

// The tasks a submission is waiting for, as buttons labelled with each task's label.
//
// Only a task whose `@events` offers the reader `complete` is shown. Another person's task is absent, not
// disabled. Tasks with outcome options are left out, since the button sends no outcome.
function SubmissionTasks({ path, onCompleted }: { path: string; onCompleted?: () => void }) {
  const { tasks, reload } = useSubmissionTasks(path);
  const doFetch = useAuthenticatedFetch();
  const notify = useNotice();
  // Each completion reports its own failure, since only it knows which task's label to name
  const { working, run } = useAsyncAction({ onFailure: () => undefined });

  const complete = (task: SubmissionTask) => run(() => sendEvent(doFetch, task.path, COMPLETE)
    .then(() => reload())
    .then(() => onCompleted?.())
    // The engine's message: only the definition knows why it refused
    .catch((error: unknown) => notify({ title: `${task.label} did not go through`, message: messageOf(error) })));

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
          disabled={working}
          onClick={() => complete(task)}
        >
          {task.label}
        </Button>
      )) }
    </Stack>
  );
}

export default SubmissionTasks;
