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

import { type ChangeEvent, useState } from "react";

import UploadIcon from "@mui/icons-material/UploadFile";
import { Alert, Box, Button, Link, Stack, Typography } from "@mui/material";

import { type FormRequirement, attachDocument } from "./submissionForm";

// Taken out of the page without being taken out of the document: the file input is the real control,
// so it has to remain focusable and nameable. `hidden` or `display: none` would drop it out of the
// tab order and leave the label naming nothing, which is the usual way this pattern breaks.
const OFFSCREEN = {
  position: "absolute" as const,
  width: 1,
  height: 1,
  overflow: "hidden",
  clipPath: "inset(50%)",
  whiteSpace: "nowrap" as const
};

// What a refused workflow event said, which the client already unwrapped from the engine's reply.
function refusal(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// Answering a document requirement: what has been attached for it, the blank to start from if it
// offers one, and a way to attach a file.
//
// The upload is an `attachDocument` event on the submission rather than a write, for the same reason
// answering a question is: a submitter can read their own request and nothing more. What may be
// attached and until when is the handler's decision — this control only reports the answer.
function DocumentUpload({ path, requirement, disabled, onAttached }: {
  path: string;
  requirement: FormRequirement;
  // Whether this reader may still change the request at all, which is the server's `editable`
  disabled: boolean;
  onAttached: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>(undefined);
  const accepted = requirement.acceptedFileTypes ?? [];
  const attached = requirement.attached ?? [];

  const upload = (file: File) => {
    setBusy(true);
    setFailure(undefined);
    attachDocument(path, requirement.name, file).then(
      () => {
        setBusy(false);
        onAttached();
      },
      (error: unknown) => {
        setBusy(false);
        // The engine's own reason: a refusal here says which file type it would not take, which is
        // the only actionable part of it
        setFailure(refusal(error));
      }
    );
  };

  return (
    <Stack spacing={1} sx={{ alignItems: "flex-start" }}>
      {failure ? <Alert severity="error" onClose={() => setFailure(undefined)}>{failure}</Alert> : null}
      {attached.length > 0
        ? <Typography variant="body2">{`Attached: ${attached.join(", ")}`}</Typography>
        : <Typography variant="body2" color="text.secondary">Nothing attached yet</Typography>}
      {requirement.template
        ? <Link href={requirement.template} download>Download the blank form</Link>
        : null}
      <Button
        component="label"
        size="small"
        variant="outlined"
        startIcon={<UploadIcon />}
        disabled={disabled || busy}
      >
        {`Attach a file for "${requirement.label || requirement.name}"`}
        <Box
          component="input"
          type="file"
          sx={OFFSCREEN}
          // What the requirement says it takes, so the file dialog offers those first. The refusal
          // that matters is still the server's: this is a hint to a dialog, not a check.
          accept={accepted.length > 0 ? accepted.join(",") : undefined}
          disabled={disabled || busy}
          onChange={(event: ChangeEvent<HTMLInputElement>) => {
            const file = event.target.files?.[0];
            if (file) {
              upload(file);
            }
            // Cleared so that picking the same file again is still a change. Without this, one
            // failed upload cannot be retried with the file that failed.
            event.target.value = "";
          }}
        />
      </Button>
    </Stack>
  );
}

export default DocumentUpload;
