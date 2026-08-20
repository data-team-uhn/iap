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

import GavelIcon from "@mui/icons-material/Gavel";
import SendIcon from "@mui/icons-material/Send";
import {
  Alert, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Stack,
  TextField, Tooltip, Typography,
} from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { type SubmissionTask, completeTask, fetchOpenTasks } from "./submissionTasks";

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

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
// A task that carries decisions is offered as one control per outcome, each opening a dialog rather
// than deciding on the spot. Not a confirmation step: it is where the decision says *why*, which no
// outcome on offer can express — a refusal usually has to give a reason and an approval may carry a
// condition. The note is optional because requiring one would make "Approved, nothing to add" into a
// sentence somebody has to invent.
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
// what a task list needs — the caller already holds the submission. It holds back the steps that
// *send* the request and nothing else: whether it was complete enough to send is settled by the time
// somebody is deciding on it, and an approver blocked by the requester's unanswered question would be
// stuck with no way to act.
function SubmissionTasks(
  { path, blockedReason, onCompleted }:
  { path: string; blockedReason?: string; onCompleted?: () => void },
) {
  const authenticatedFetch = useAuthenticatedFetch();
  const [ tasks, setTasks ] = useState<SubmissionTask[]>([]);
  const [ error, setError ] = useState<string>();
  const [ busy, setBusy ] = useState(false);
  const [ deciding, setDeciding ] = useState<{ task: SubmissionTask; outcome: string }>();
  const [ note, setNote ] = useState("");

  // Failing to read this is quiet, and quiet in the same way wherever it happens: not being able to
  // tell what a request is waiting for means offering nothing, which is what an empty list already
  // says. The error below is for a refusal — the engine saying no — which is a different thing, and
  // the only one worth putting in somebody's way.
  const load = useCallback(() => fetchOpenTasks(path).then(setTasks, () => setTasks([])), [ path ]);

  useEffect(() => {
    void load();
  }, [ load ]);

  const complete = (task: SubmissionTask, outcome?: string, note?: string) => {
    setBusy(true);
    setError(undefined);
    completeTask(authenticatedFetch, task, outcome, note)
      .then(() => load())
      .then(() => onCompleted?.())
      .catch((e: unknown) => setError(message(e)))
      .finally(() => setBusy(false));
  };

  // Closing forgets what was typed, whichever way it closes: a note is about the decision it was
  // written for, and one abandoned here reappearing under the next decision would be somebody's
  // reason attached to something they did not say it about
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

  const steps = tasks.filter(task => task.outcomeOptions.length === 0);
  const decisions = tasks.filter(task => task.outcomeOptions.length > 0);
  if (steps.length === 0 && decisions.length === 0 && !error) {
    return null;
  }

  return (
    <Stack spacing={1}>
      { steps.map(task => (
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
      { decisions.map(task => (
        <Stack key={task.path} spacing={1}>
          {/* The task says what is being decided; the buttons say what it may be decided with. Named
              by the definition, so a process whose outcomes are `endorsed` and `returned` reads that
              way here without a line of code changing. */}
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
      { error && <Alert severity="error" onClose={() => setError(undefined)}>{error}</Alert> }
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
