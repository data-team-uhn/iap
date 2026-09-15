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
  // Called with the path of the submission that was raised, so the caller can open it
  onCreated: (path: string) => void;
}

// Raising a submission: pick what is being submitted against, name it, and let the workflow
// engine do the rest. The POST goes to the /Submissions homepage rather than to any CRUD
// endpoint. What a POST there means is a system workflow definition's to decide, so this dialog
// knows only the two things that definition asks for.
//
// Mounted only while it is open, so each opening starts from nothing. What is on offer is read
// afresh, and a half-filled attempt is not still sitting there next time.
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
      // Trimmed because the submission's name is derived from its title, and surrounding
      // whitespace would be carried into it
      body: new URLSearchParams({ title: title.trim(), schemaVersion: selected }),
    })
      .then(async response => {
        // Answered with a redirect to what it created, which fetch has already followed: this
        // response describes that page, so a read that failed here says nothing about whether the
        // submission exists. Asked before the status, or a created submission reads as refused.
        if (response.redirected) {
          onCreated(new URL(response.url).pathname);
          return;
        }
        if (!response.ok) {
          // The engine answers a refusal with the reason: no applicable workflow, not allowed to
          // raise this, or a payload it will not accept. Its words are already in the submitter's
          // terms, so they are shown as they stand rather than described
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
        { /* Distinguished from a failed load on purpose: "nothing is open for submissions" is an
             answer, and looks identical to a broken dialog if it is left blank */ }
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
                  // A refusal was about the choice that was made, so it does not stand over a new one
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
                          <Typography component="span" variant="body2" color="text.secondary"
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
          // Both are required by the workflow that raises the submission, so a request that
          // would certainly be refused is not offered
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
