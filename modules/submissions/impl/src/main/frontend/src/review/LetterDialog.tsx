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

import {
  Alert,
  Button,
  DialogActions,
  DialogContent,
  DialogContentText,
  Link,
  Stack,
  TextField,
  Typography,
} from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";
import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";

import type { Letter } from "./reviewData";

// A letter being worked on, kept in the browser until it is sent: what the reviewer made of it, and the draft it
// started from, to tell when their issues have changed since
interface Kept {
  text: string;
  from: string;
}

// A draft without its first line, the date, which changes from one day to the next when the issues do not
const issuesOf = (draft: string) => draft.slice(draft.indexOf("\n"));

const keyOf = (submission: string) => `iap.review.letter.${submission}`;

function kept(submission: string): Kept | undefined {
  try {
    const stored = localStorage.getItem(keyOf(submission));
    return stored ? JSON.parse(stored) as Kept : undefined;
  } catch {
    return undefined;
  }
}

function keep(submission: string, letter?: Kept) {
  try {
    if (letter) {
      localStorage.setItem(keyOf(submission), JSON.stringify(letter));
    } else {
      localStorage.removeItem(keyOf(submission));
    }
  } catch {
    // Not kept then, as in a private window
  }
}

// PRETOTYPE. The reviewer's letter to the study team, editable before it goes: sending it asks the study team for
// changes, through the reviewer's own review task, with the letter as the decision's note. Changes to it are kept
// when it is closed, so it can be worked on over several sittings.
export default function LetterDialog({ open, submission, draft, issueCount, onSend, onClose }: {
  open: boolean;
  // The submission's path, which the letter being worked on is kept by
  submission: string;
  draft: string;
  issueCount: number;
  onSend: (letter: string) => Promise<void>;
  onClose: () => void;
}) {
  const [ worked, setWorked ] = useState(() => kept(submission));
  const letter = worked?.text ?? draft;
  const [ confirming, setConfirming ] = useState(false);
  const change = (text: string) => {
    const next = text === draft ? undefined : { text, from: worked?.from ?? draft };
    setWorked(next);
    keep(submission, next);
  };
  const startAgain = () => {
    setWorked(undefined);
    keep(submission);
  };
  const send = async () => {
    await onSend(letter);
    keep(submission);
    onClose();
  };
  return (
    <ResponsiveDialog open={open} onClose={onClose} title="Request changes" width="md" withCloseButton>
      <DialogContent>
        <Stack spacing={2}>
          <Typography variant="description">
            {issueCount === 0 ? "You haven't raised any issues for the study team yet, so the letter lists none."
              : `Drafted from the ${issueCount === 1 ? "issue" : `${issueCount} issues`} you raised for the study team,
              the most serious first.`} Change anything before you send it. Your changes are kept if you close it.
            Sending it asks the study team for changes, and pauses the clock until they answer.
          </Typography>
          {worked && issuesOf(worked.from) !== issuesOf(draft) && (
            <Alert severity="info">
              Your issues changed since you started on this letter.{" "}
              <Link component="button" variant="body2" onClick={startAgain}>Draft it again from your issues</Link>
            </Alert>
          )}
          <TextField
            multiline fullWidth minRows={18} value={letter} onChange={event => change(event.target.value)}
            slotProps={{ htmlInput: { "aria-label": "The letter" } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => void navigator.clipboard.writeText(letter)}>Copy</Button>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={!letter.trim()} onClick={() => setConfirming(true)}>
          Send
        </Button>
      </DialogActions>
      {confirming && (
        <ConfirmActionDialog
          title="Send the letter to the study team?" confirmLabel="Send" workingLabel="Sending…"
          onConfirm={send} onClose={() => setConfirming(false)}
        >
          <DialogContentText>
            It asks them for changes, and pauses the clock until they answer. You can read it again after, but not
            change it.
          </DialogContentText>
        </ConfirmActionDialog>
      )}
    </ResponsiveDialog>
  );
}

// A letter already sent, to read again, as when the study team answers it
export function SentLetter({ letter, onClose }: { letter: Letter; onClose: () => void }) {
  const sent = new Date(letter.at).toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" });
  return (
    <ResponsiveDialog open onClose={onClose} title="Your letter" width="md" withCloseButton>
      <DialogContent>
        <Typography variant="description" sx={{ display: "block", mb: 2 }}>
          {letter.outcome === "approved" ? "Sent with your approval" : "Sent with your request for changes"} on{" "}
          {sent}.
        </Typography>
        <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>{letter.text}</Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => void navigator.clipboard.writeText(letter.text)}>Copy</Button>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}
