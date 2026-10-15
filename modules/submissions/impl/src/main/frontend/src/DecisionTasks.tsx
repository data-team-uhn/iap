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

import GavelIcon from "@mui/icons-material/Gavel";
import {
  Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Stack, TextField, Typography,
} from "@mui/material";

import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";
import { offers, sendEvent } from "@iap/frontend-commons/workflowEvents";

import { COMPLETE_EVENT, type SubmissionTask } from "./taskModel";
import { useSubmissionTasks } from "./useSubmissionTasks";

// The parameter naming whom an event is sent for; `:`-prefixed, so it stays out of the event's payload
const ON_BEHALF_OF = ":onBehalfOf";

// An outcome as a person reads it: the definition's own word, capitalised
function label(outcome: string): string {
  return outcome.charAt(0).toUpperCase() + outcome.substring(1);
}

// The decisions a submission is waiting for, one button per outcome: the tasks with outcome options,
// which `SubmissionTasks` leaves out. Each button opens a dialog where the decision can be recorded on
// behalf of someone else, which is what a delegate does for a person who cannot get to it themselves.
// A task is shown when it is the reader's to decide, or when they may decide it for someone else.
function DecisionTasks({ path, onCompleted }: { path: string; onCompleted?: () => void }) {
  const { tasks, reload } = useSubmissionTasks(path);
  const doFetch = useAuthenticatedFetch();
  const notify = useNotice();
  const { working, run } = useAsyncAction({ onFailure: () => undefined });
  const [ deciding, setDeciding ] = useState<{ task: SubmissionTask; outcome: string }>();
  const [ onBehalfOf, setOnBehalfOf ] = useState("");

  const mine = (task: SubmissionTask) => offers(task, COMPLETE_EVENT);

  // Closing forgets whom the decision was for, so it cannot carry over to the next one unnoticed
  const close = () => {
    setDeciding(undefined);
    setOnBehalfOf("");
  };

  const decide = () => {
    if (!deciding) {
      return;
    }
    const { task, outcome } = deciding;
    const params: Record<string, string> = { outcome };
    if (onBehalfOf.trim()) {
      params[ON_BEHALF_OF] = onBehalfOf.trim();
    }
    close();
    void run(() => sendEvent(doFetch, task.path, COMPLETE_EVENT, params)
      .then(() => {
        onCompleted?.();
        return reload();
      })
      // The engine's message: only it knows why it refused
      .catch((error: unknown) => notify({ title: `${task.label} did not go through`, message: messageOf(error) })));
  };

  const offered = tasks.filter(task => task.outcomeOptions.length > 0 && (mine(task) || task["@delegable"]));
  if (offered.length === 0) {
    return null;
  }
  return (
    <Stack spacing={1}>
      { offered.map(task => (
        <Stack key={task.path} spacing={1}>
          <Typography variant="subtitle2">{task.label}</Typography>
          <Stack direction="row" spacing={1}>
            { task.outcomeOptions.map(outcome => (
              <Button
                key={outcome}
                variant="contained"
                startIcon={<GavelIcon />}
                disabled={working}
                onClick={() => setDeciding({ task, outcome })}
              >
                {label(outcome)}
              </Button>
            )) }
          </Stack>
        </Stack>
      )) }
      <Dialog open={deciding !== undefined} onClose={close} fullWidth>
        <DialogTitle>{deciding?.task.label}</DialogTitle>
        <DialogContent>
          <DialogContentText gutterBottom>
            {`Recording: ${label(deciding?.outcome ?? "")}`}
          </DialogContentText>
          <TextField
            label="On behalf of"
            // Not theirs to decide, so only for someone it is
            required={deciding !== undefined && !mine(deciding.task)}
            helperText={deciding !== undefined && mine(deciding.task)
              ? "The user id of whoever this decision is for. Leave blank to decide as yourself."
              : "The user id of whoever this decision is for. It is not yours so you cannot make it as yourself."}
            value={onBehalfOf}
            onChange={(event) => setOnBehalfOf(event.target.value)}
            fullWidth
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={close}>Cancel</Button>
          <Button
            variant="contained"
            disabled={deciding === undefined || (!mine(deciding.task) && !onBehalfOf.trim())}
            onClick={decide}
          >
            Record decision
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

export default DecisionTasks;
