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

// How long the browser may look at a file before sending it unchecked. The check only advises, and
// the server checks again.
const CHECK_TIMEOUT_MS = 30_000;

// What is wrong with the file, or undefined when nothing is or the check ran out of time. Checked
// against the server's own limits, read within the same time.
async function checkFile(
  file: File, accepted: string[], doFetch: AuthenticatedFetch): Promise<string | undefined> {
  const signal = AbortSignal.timeout(CHECK_TIMEOUT_MS);
  try {
    const limits = await fetchUploadLimits(doFetch, signal);
    return await validateUpload(file, accepted, limits, signal);
  } catch (error) {
    if (error instanceof DOMException && error.name === "TimeoutError") {
      return undefined;
    }
    throw error;
  }
}

interface FileButtonProps {
  label: string;
  // The input's own name, when the visible label alone would not say which requirement it is for
  ariaLabel?: string;
  accepted: string[];
  disabled: boolean;
  title?: string;
  onPick: (file: File) => void;
}

// A button that opens the file dialog and hands over the file picked.
function FileButton({ label, ariaLabel, accepted, disabled, title, onPick }: FileButtonProps) {
  return (
    <Button
      component="label"
      size="small"
      variant="outlined"
      startIcon={<UploadIcon />}
      disabled={disabled}
      title={title}
    >
      {label}
      <Box
        component="input"
        type="file"
        sx={OFFSCREEN}
        aria-label={ariaLabel}
        // Only a hint for the file dialog. The server checks the type
        accept={accepted.length > 0 ? accepted.join(",") : undefined}
        disabled={disabled}
        onChange={(event: ChangeEvent<HTMLInputElement>) => {
          const file = event.target.files?.[0];
          if (file) {
            onPick(file);
          }
          // Cleared so that picking the same file again is still a change. Without this, one
          // failed upload cannot be retried with the file that failed.
          event.target.value = "";
        }}
      />
    </Button>
  );
}

export interface DocumentUploadProps {
  path: string;
  requirement: DocumentRequirement;
  // Whether this reader may still change the request at all, which is the server's `editable`
  disabled: boolean;
  // Whether the documents are being parsed or read. A new version then would leave the reading
  // working on a file that is no longer the latest, so this does not offer it.
  reading?: boolean;
  onAttached: () => void;
}

// Answering a document requirement: what has been attached for it, and a way to attach a file. A file
// is never removed: a newer upload for the same requirement becomes a new version of the document.
function DocumentUpload({ path, requirement, disabled, reading = false, onAttached }: DocumentUploadProps) {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>(undefined);
  const doFetch = useAuthenticatedFetch();
  const accepted = requirement.acceptedFileTypes;
  const attached = requirement.attached;
  const name = requirement.label || requirement.name;

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

  // Reloads the form on success, since what it asks and what is attached may have changed
  const run = (change: () => Promise<void>) => {
    setBusy(true);
    setFailure(undefined);
    change().then(
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

  // Checked here first, so a file the server would refuse, or the parser could not read, is refused
  // in a moment rather than after the upload
  const upload = (file: File) => run(async () => {
    const problem = await checkFile(file, accepted, doFetch);
    if (problem !== undefined) {
      throw new Error(problem);
    }
    await attachDocument(doFetch, path, requirement.name, file);
  });

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
              disabled={disabled || busy || reading}
              title={reading ? "Wait until the document has been read, or stop the reading" : undefined}
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
