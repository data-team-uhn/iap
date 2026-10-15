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
import {
  Alert, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Stack, TextField, Typography,
} from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { type SubmissionTask, completeTask, fetchOpenTasks } from "./submissionTasks";

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// An outcome as a person reads it: the definition's own word, capitalised
function label(outcome: string): string {
  return outcome.charAt(0).toUpperCase() + outcome.substring(1);
}

// The decisions a submission is waiting for, one button per outcome. Each opens a dialog where the
// decision can be recorded on behalf of someone else, which is what a delegate does for a person who
// cannot get to it themselves.
function SubmissionTasks({ path, onCompleted }: { path: string; onCompleted?: () => void }) {
  const doFetch = useAuthenticatedFetch();
  const [ tasks, setTasks ] = useState<SubmissionTask[]>([]);
  const [ error, setError ] = useState<string>();
  const [ busy, setBusy ] = useState(false);
  const [ deciding, setDeciding ] = useState<{ task: SubmissionTask; outcome: string }>();
  const [ onBehalfOf, setOnBehalfOf ] = useState("");

  // Not being able to tell what a request is waiting for means offering nothing, which an empty list
  // already says; the error below is kept for the engine refusing a decision
  const load = useCallback(() => fetchOpenTasks(doFetch, path).then(setTasks, () => setTasks([])), [ doFetch, path ]);

  useEffect(() => {
    void load();
  }, [ load ]);

  // Closing forgets whom the decision was for, so it cannot carry over to the next one unnoticed
  const close = () => {
    setDeciding(undefined);
    setOnBehalfOf("");
  };

  const decide = () => {
    if (!deciding) {
      return;
    }
    setBusy(true);
    setError(undefined);
    completeTask(doFetch, deciding.task, deciding.outcome, onBehalfOf)
      .then(() => load())
      .then(() => onCompleted?.())
      .catch((e: unknown) => setError(message(e)))
      .finally(() => setBusy(false));
    close();
  };

  if (tasks.length === 0 && !error) {
    return null;
  }

  return (
    <Stack spacing={1}>
      { tasks.map(task => (
        <Stack key={task.path} spacing={1}>
          <Typography variant="subtitle2">{task.label}</Typography>
          <Stack direction="row" spacing={1}>
            { task.outcomes.map(outcome => (
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
            label="On behalf of"
            // Not theirs to decide, so only for someone it is
            required={!deciding?.task.mine}
            helperText={deciding?.task.mine
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
            disabled={!deciding?.task.mine && !onBehalfOf.trim()}
            onClick={decide}
          >
            Record decision
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

export default SubmissionTasks;
