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

import { useCallback, useState } from "react";

import {
  Alert,
  Box,
  Button,
  CircularProgress,
  DialogActions,
  DialogContent,
  DialogContentText,
  FormControl,
  FormControlLabel,
  FormLabel,
  Radio,
  RadioGroup,
  Stack,
  TextField,
  Typography,
} from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { isNotAuthenticated, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, RequestError } from "@iap/frontend-commons/requestFailure";

import { useSchemas } from "./useSchemas";

interface NewSubmissionDialogProps {
  onClose: () => void;
  // Called with the path of the submission raised, or with an empty string when the engine created
  // nothing to open
  onCreated: (path: string) => void;
}

// Raises a submission. The submitter picks a schema open for submissions and gives a title, and the
// dialog posts both to /Submissions as a `create` event. It reads the schemas on offer when it mounts.
function NewSubmissionDialog({ onClose, onCreated }: NewSubmissionDialogProps) {
  const doFetch = useAuthenticatedFetch();
  const { choices, loading, error: loadError } = useSchemas();
  const [ selected, setSelected ] = useState("");
  const [ title, setTitle ] = useState("");
  const [ submitting, setSubmitting ] = useState(false);
  const [ submitError, setSubmitError ] = useState<string>();

  const submit = useCallback(() => {
    setSubmitting(true);
    setSubmitError(undefined);
    doFetch("/Submissions", {
      method: "POST",
      body: new URLSearchParams({ title: title.trim(), schemaVersion: selected }),
    })
      .then(async response => {
        // The engine redirects to the submission it created, and fetch has followed. The status is
        // the created page's, and says nothing about the creation. Checked before the status, or a
        // created submission could read as refused.
        if (response.redirected) {
          onCreated(new URL(response.url).pathname);
          return;
        }
        if (!response.ok) {
          // A refusal carries the engine's reason, such as "A title is required", shown as it stands
          const body = (await response.json().catch(() => ({}))) as { error?: string };
          if (body.error) {
            setSubmitError(body.error);
            return;
          }
          throw new RequestError(response.status);
        }
        // Accepted without creating anything, so there is nowhere to send the submitter
        onCreated("");
      })
      .catch((error: unknown) => setSubmitError(isNotAuthenticated(error)
        ? "You are no longer signed in, so the submission was not raised. Sign in and try again."
        : describeRequestFailure(error)))
      .finally(() => setSubmitting(false));
  }, [ doFetch, onCreated, selected, title ]);

  const empty = !loading && !loadError && choices.length === 0;

  return (
    <ResponsiveDialog title="New submission" withCloseButton open onClose={onClose}
      closeDisabled={submitting}>
      <DialogContent dividers>
        { loading && (
          <Box sx={{ display: "flex", justifyContent: "center", p: 2 }}>
            <CircularProgress aria-label="Loading the schemas" />
          </Box>
        ) }
        { loadError && <Alert severity="error">{loadError}</Alert> }
        { /* Said, since an empty dialog would look broken */ }
        { empty && (
          <DialogContentText>
            Nothing is currently open for submissions.
          </DialogContentText>
        ) }
        { choices.length > 0 && (
          <Stack spacing={2}>
            <FormControl>
              <FormLabel id="new-submission-schema">What is being submitted</FormLabel>
              <RadioGroup
                aria-labelledby="new-submission-schema"
                value={selected}
                onChange={event => {
                  setSelected(event.target.value);
                  // A refusal was about the earlier choice, so a new choice clears it
                  setSubmitError(undefined);
                }}
              >
                { choices.map(choice => (
                  <FormControlLabel
                    key={choice.path}
                    value={choice.path}
                    control={<Radio />}
                    label={
                      <>
                        <Typography component="span">{`${choice.title} ${choice.version}`.trim()}</Typography>
                        { choice.description && (
                          <Typography component="span" variant="description"
                            sx={{ display: "block" }}>{choice.description}</Typography>
                        ) }
                      </>
                    }
                  />
                )) }
              </RadioGroup>
            </FormControl>
            <TextField
              label="Title"
              required
              value={title}
              onChange={event => setTitle(event.target.value)}
              helperText="How you will recognize this request in your list"
            />
            { submitError && <Alert severity="error">{submitError}</Alert> }
          </Stack>
        ) }
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>Cancel</Button>
        <Button
          variant="contained"
          // The create workflow refuses a submission without both
          disabled={submitting || !selected || !title.trim()}
          onClick={submit}
        >
          Create
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default NewSubmissionDialog;
