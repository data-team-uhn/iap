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

import { useEffect, useState } from "react";

import GavelIcon from "@mui/icons-material/Gavel";
import SendOutlinedIcon from "@mui/icons-material/SendOutlined";
import {
  Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Stack, TextField,
  Tooltip, Typography,
} from "@mui/material";

import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { offers, sendEvent } from "@iap/frontend-commons/workflowEvents";

import { COMPLETE, type SubmissionTask, completionParams } from "./taskModel";
import { useSubmissionTasks } from "./useSubmissionTasks";

// An outcome as a person reads it. The values come from the definition, in whatever words it chose,
// so this only fixes the capitalisation — inventing a phrasing per outcome would mean holding a list
// of them here, which is the definition's business and not this page's.
function label(outcome: string): string {
  return outcome.charAt(0).toUpperCase() + outcome.substring(1);
}

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
// decides the act itself.
//
// A task that carries decisions is offered as one control per outcome, each opening a dialog rather
// than deciding on the spot. Not a confirmation step: it is where the decision says *why*, which no
// outcome on offer can express. The note is optional.
//
// `blockedReason` says whether the request is ready to be sent at all, which the save workflow has
// already recorded on the submission. The caller passes it, since it already holds the submission.
// It holds back the steps that *send* the request and nothing else: an approver blocked by the
// requester's unanswered question would be stuck with no way to act.
function SubmissionTasks(
  { path, blockedReason, onCompleted, requirement, refreshToken }: {
    path: string;
    blockedReason?: string;
    onCompleted?: () => void;
    requirement?: string;
    // Bumped by the page when the request changed, so a step that another control just opened shows
    // up here too, such as the page's "send" step once the reading is done.
    refreshToken?: number;
  },
) {
  const { tasks, reload } = useSubmissionTasks(path);
  const doFetch = useAuthenticatedFetch();
  const notify = useNotice();
  const [ busy, setBusy ] = useState(false);
  const [ deciding, setDeciding ] = useState<{ task: SubmissionTask; outcome: string }>();
  const [ note, setNote ] = useState("");

  // Read again when the page says the request changed
  useEffect(() => {
    if (refreshToken !== undefined) {
      void reload();
    }
  }, [ reload, refreshToken ]);

  const complete = (task: SubmissionTask, outcome?: string, note?: string) => {
    setBusy(true);
    sendEvent(doFetch, task.path, COMPLETE, completionParams(outcome, note))
      .then(() => reload())
      .then(() => onCompleted?.())
      // The engine's own words: it is the definition that refused, and only it knows why
      .catch((error: unknown) => notify({ title: `${task.label} did not go through`, message: messageOf(error) }))
      .finally(() => setBusy(false));
  };

  // Closing forgets what was typed, whichever way it closes: a note is about the decision it was
  // written for, and must not reappear under the next one
  const close = () => {
    setDeciding(undefined);
    setNote("");
  };

  const decide = () => {
    if (deciding) {
      complete(deciding.task, deciding.outcome, note);
    }
    close();
  };

  // A task says which requirement it is about, and this says which of them this placement shows: the
  // page passes nothing and gets the tasks about the request as a whole, a requirement passes its own
  // name and gets the ones that act on it. Every task is offered exactly once.
  const offered = tasks.filter(task => offers(task, COMPLETE) && task.requirement === requirement);
  const steps = offered.filter(task => task.outcomeOptions.length === 0);
  const decisions = offered.filter(task => task.outcomeOptions.length > 0);
  if (offered.length === 0) {
    return null;
  }
  // A step offered under a requirement is hidden until that requirement is ready for it, rather than
  // shown greyed out: the control that would make it possible is right above it. A step among the
  // page's own actions stays put and says why it cannot be taken.
  if (requirement != undefined && blockedReason != undefined) {
    return null;
  }

  return (
    <Stack spacing={1}>
      { steps.length > 0 && (
        <Stack direction="row" spacing={1}>
          { steps.map(task => (
            // Wrapped, because a disabled button fires no events and so shows no tooltip of its own
            <Tooltip key={task.path} title={blockedReason ?? ""}>
              <span>
                <Button
                  variant="contained"
                  startIcon={<SendOutlinedIcon />}
                  disabled={busy || blockedReason != undefined}
                  onClick={() => complete(task)}
                >
                  {task.label}
                </Button>
              </span>
            </Tooltip>
          )) }
        </Stack>
      ) }
      { decisions.map(task => (
        <Stack key={task.path} spacing={1}>
          {/* The task says what is being decided; the buttons say what it may be decided with, in
              the definition's own words. */}
          <Typography variant="subtitle2">{task.label}</Typography>
          <Stack direction="row" spacing={1}>
            { task.outcomeOptions.map(outcome => (
              <Button
                key={outcome}
                variant="contained"
                startIcon={<GavelIcon />}
                disabled={busy}
                onClick={() => setDeciding({ task, outcome })}
              >
                {label(outcome)}
              </Button>
            )) }
          </Stack>
        </Stack>
      )) }
      <Dialog open={deciding != undefined} onClose={close} fullWidth>
        <DialogTitle>{deciding?.task.label}</DialogTitle>
        <DialogContent>
          <DialogContentText gutterBottom>
            {`Recording: ${label(deciding?.outcome ?? "")}`}
          </DialogContentText>
          <TextField
            label="Note"
            helperText="Anything the decision should be remembered with. Optional."
            value={note}
            onChange={(event) => setNote(event.target.value)}
            multiline
            minRows={3}
            fullWidth
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={close}>Cancel</Button>
          <Button variant="contained" onClick={decide}>Record decision</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

export default SubmissionTasks;
