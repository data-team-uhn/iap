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

import { Alert, Button, DialogActions, DialogContent, Stack, TextField, Typography } from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";

// PRETOTYPE. The reviewer's letter to the study team, editable before it goes: sending it asks the study team for
// changes, through the reviewer's own review task, with the letter as the decision's note.
export default function LetterDialog({ open, draft, issueCount, onSend, onClose }: {
  open: boolean;
  draft: string;
  issueCount: number;
  onSend: (letter: string) => Promise<void>;
  onClose: () => void;
}) {
  const [ letter, setLetter ] = useState(draft);
  const [ sending, setSending ] = useState(false);
  const [ error, setError ] = useState<string>();
  const send = async () => {
    setSending(true);
    setError(undefined);
    try {
      await onSend(letter);
      onClose();
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setSending(false);
    }
  };
  return (
    <ResponsiveDialog open={open} onClose={onClose} title="Request changes" width="md" withCloseButton>
      <DialogContent>
        <Stack spacing={2}>
          <Typography variant="description">
            Drafted from the {issueCount} issues raised for the study team, the most serious first. Change anything
            before you send it. Sending it asks the study team for changes, and pauses the clock until they answer.
          </Typography>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField
            multiline fullWidth minRows={18} value={letter} onChange={event => setLetter(event.target.value)}
            slotProps={{ htmlInput: { "aria-label": "The letter" } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => void navigator.clipboard.writeText(letter)}>Copy</Button>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={sending || !letter.trim()} onClick={() => void send()}>
          Send
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}
