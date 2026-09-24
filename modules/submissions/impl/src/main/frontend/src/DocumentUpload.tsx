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

import DeleteIcon from "@mui/icons-material/DeleteOutlined";
import UploadIcon from "@mui/icons-material/UploadFile";
import { Alert, Box, Button, Link, Stack, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";

import { validateUpload } from "./fileValidation";
import {
  type DocumentRequirement,
  attachDocument,
  detachDocument,
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

export interface DocumentUploadProps {
  path: string;
  requirement: DocumentRequirement;
  // Whether this reader may still change the request at all, which is the server's `editable`
  disabled: boolean;
  // Whether the documents are being parsed or read. A file removed then would leave the reading
  // waiting on a parse that lands on nothing, so the server refuses it and this does not offer it.
  reading?: boolean;
  onAttached: () => void;
}

// Answering a document requirement: what has been attached for it, and a way to attach a file.
function DocumentUpload({ path, requirement, disabled, reading = false, onAttached }: DocumentUploadProps) {
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

  // Both reload the form on success, since what it asks and what is attached may have changed
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

  // Checked here before it is sent, so a file that cannot be read is refused in a moment rather than
  // after a slow upload. The server checks again; this is only the quick half.
  const upload = (file: File) => run(() => validateUpload(file, accepted).then(problem => {
    if (problem !== undefined) {
      throw new Error(problem);
    }
    return attachDocument(doFetch, path, requirement.name, file);
  }));
  const remove = () => run(() => detachDocument(doFetch, path, requirement.name));

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
            <Button
              size="small"
              color="inherit"
              startIcon={<DeleteIcon />}
              disabled={disabled || busy || reading}
              title={reading ? "Wait until the document has been read, or abort the reading" : undefined}
              onClick={remove}
            >
              Remove
            </Button>
          </Stack>
        )
        : (
          // Only offered while nothing is attached: a wrong file is removed first, then attached again
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
        )}
    </Stack>
  );
}

export default DocumentUpload;
