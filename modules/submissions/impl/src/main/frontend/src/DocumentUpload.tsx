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

import { type ChangeEvent, useEffect, useState } from "react";

import UploadIcon from "@mui/icons-material/UploadFile";
import { Alert, Box, Button, Link, Stack, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";

import { type DocumentRequirement, attachDocument, describeNothingAttached } from "./submissionForm";

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

export interface DocumentUploadProps {
  path: string;
  requirement: DocumentRequirement;
  // Whether this reader may still change the request at all, which is the server's `editable`
  disabled: boolean;
  onAttached: () => void;
}

// Answering a document requirement: what has been attached for it, the template it offers if any,
// and a way to attach a file.
function DocumentUpload({ path, requirement, disabled, onAttached }: DocumentUploadProps) {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>(undefined);
  const doFetch = useAuthenticatedFetch();
  const accepted = requirement.acceptedFileTypes;
  const attached = requirement.attached;

  // Leaving the page aborts an upload still on its way, so the browser asks first. Moving within the
  // app does not abort it, and the file still arrives.
  useEffect(() => {
    if (!busy) {
      return undefined;
    }
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [busy]);

  const upload = (file: File) => {
    setBusy(true);
    setFailure(undefined);
    attachDocument(doFetch, path, requirement.name, file).then(
      () => {
        setBusy(false);
        onAttached();
      },
      (error: unknown) => {
        setBusy(false);
        // The engine's own reason, such as which file type it would not take
        setFailure(messageOf(error));
      }
    );
  };

  return (
    <Stack spacing={1} sx={{ alignItems: "flex-start" }}>
      {failure ? <Alert severity="error" onClose={() => setFailure(undefined)}>{failure}</Alert> : null}
      {attached.length > 0
        ? <Typography variant="body2">{`Attached: ${attached.join(", ")}`}</Typography>
        : (
          <Typography variant="placeholder">
            {describeNothingAttached(requirement)}
          </Typography>
        )}
      {requirement.template
        ? <Link href={requirement.template} download={requirement.templateName ?? true}>Download the template</Link>
        : null}
      <Button
        component="label"
        size="small"
        variant="outlined"
        startIcon={<UploadIcon />}
        disabled={disabled || busy}
      >
        {/* Uploading again adds a new version of the same document */}
        {`${attached.length > 0 ? "Replace the file" : "Attach a file"} for "${requirement.label || requirement.name}"`}
        <Box
          component="input"
          type="file"
          sx={OFFSCREEN}
          // Only a hint for the file dialog. The server checks the type
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
