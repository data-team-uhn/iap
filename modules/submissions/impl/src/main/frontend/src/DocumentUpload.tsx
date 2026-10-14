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

import { type ChangeEvent, Fragment, useEffect, useState } from "react";

import UploadIcon from "@mui/icons-material/UploadFile";
import { Alert, Box, Button, Link, Stack, Typography } from "@mui/material";

import { validateUpload } from "@iap/frontend-commons/fileValidation";
import { type AuthenticatedFetch, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { fetchUploadLimits } from "@iap/frontend-commons/uploadLimits";

import {
  type DocumentRequirement,
  attachDocument,
  toFileUrl
} from "./submissionForm";

// Keeps the file input out of sight. It is the real control, so it stays focusable and named.
// `hidden` or `display: none` would take it out of the tab order and leave the label naming nothing.
const OFFSCREEN = {
  position: "absolute" as const,
  width: 1,
  height: 1,
  overflow: "hidden",
  clipPath: "inset(50%)",
  whiteSpace: "nowrap" as const
};

interface FileButtonProps {
  label: string;
  // The input's own name, when the visible label alone would not say which requirement it is for
  ariaLabel?: string;
  accepted: string[];
  disabled: boolean;
  onPick: (file: File) => void;
}

// A button that opens the file dialog and hands over the file picked.
function FileButton({ label, ariaLabel, accepted, disabled, onPick }: FileButtonProps) {
  return (
    <Button component="label" size="small" variant="outlined" startIcon={<UploadIcon />} disabled={disabled}>
      {label}
      <Box
        component="input"
        type="file"
        sx={OFFSCREEN}
        aria-label={ariaLabel}
        // Only a hint for the file dialog. The type is checked before sending, and again by the server.
        accept={accepted.length > 0 ? accepted.join(",") : undefined}
        disabled={disabled}
        onChange={(event: ChangeEvent<HTMLInputElement>) => {
          const file = event.target.files?.[0];
          if (file) {
            onPick(file);
          }
          // Cleared, so that picking the same file again is a change, and a failed upload can be
          // retried
          event.target.value = "";
        }}
      />
    </Button>
  );
}

// How long the browser check of a file may take, in milliseconds. Past it, the file is sent unchecked,
// and the server still checks its type and size.
const CHECK_TIMEOUT_MS = 30_000;

// What is wrong with the file, judged by the server's own limits, or undefined when nothing is or the
// check ran out of time. Reading the limits counts against the same time as the check.
async function checkFile(
  file: File, accepted: string[], doFetch: AuthenticatedFetch): Promise<string | undefined> {
  const deadline = performance.now() + CHECK_TIMEOUT_MS;
  const limits = await fetchUploadLimits(doFetch, AbortSignal.timeout(CHECK_TIMEOUT_MS));
  return validateUpload(file, { accepted, limits, timeoutMs: Math.max(0, deadline - performance.now()) });
}

export interface DocumentUploadProps {
  path: string;
  requirement: DocumentRequirement;
  // Whether the reader may no longer change the request: the form's `editable`, negated
  disabled: boolean;
  onAttached: () => void;
}

// Answers a document requirement: what has been attached for it, and a way to attach a file. It
// offers no removal. A newer upload for the same requirement becomes a new version of the document.
function DocumentUpload({ path, requirement, disabled, onAttached }: DocumentUploadProps) {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>(undefined);
  const doFetch = useAuthenticatedFetch();
  const accepted = requirement.acceptedFileTypes;
  const attached = requirement.attached;
  const name = requirement.label || requirement.name;

  // Leaving the page aborts an upload still on its way, so the browser asks first. Moving within the
  // app does not abort it.
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
    checkFile(file, accepted, doFetch).then(problem => {
      if (problem !== undefined) {
        throw new Error(problem);
      }
      return attachDocument(doFetch, path, requirement.name, file);
    }).then(
      () => {
        setBusy(false);
        onAttached();
      },
      (error: unknown) => {
        setBusy(false);
        // Why the browser check or the engine refused the file, or why the request failed
        setFailure(messageOf(error));
      }
    );
  };

  return (
    <Stack spacing={1} sx={{ alignItems: "flex-start" }}>
      {failure ? <Alert severity="error" onClose={() => setFailure(undefined)}>{failure}</Alert> : null}
      {attached.length > 0
        ? (
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography variant="body2">
              {"Attached: "}
              {attached.map((document, index) => (
                <Fragment key={document.path ?? document.title}>
                  {index > 0 ? ", " : null}
                  {document.path
                    ? <Link href={toFileUrl(document.path)} download={document.title}>{document.title}</Link>
                    : document.title}
                </Fragment>
              ))}
            </Typography>
            <FileButton
              label="Upload a new version"
              ariaLabel={`Upload a new version for "${name}"`}
              accepted={accepted}
              disabled={disabled || busy}
              onPick={upload}
            />
          </Stack>
        )
        : (
          <FileButton
            label={`Attach a file for "${name}"`}
            accepted={accepted}
            disabled={disabled || busy}
            onPick={upload}
          />
        )}
    </Stack>
  );
}

export default DocumentUpload;
