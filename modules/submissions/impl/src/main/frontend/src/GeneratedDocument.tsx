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

import { Fragment, useState } from "react";

import GenerateIcon from "@mui/icons-material/NoteAdd";
import { Alert, Button, Link, Stack, Typography } from "@mui/material";

import { validateUpload } from "@iap/frontend-commons/fileValidation";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import type { Enqueue } from "@iap/frontend-commons/useWorkQueue";
import { sendEvent } from "@iap/frontend-commons/workflowEvents";

import { CHECK_TIMEOUT_MS, FileButton } from "./DocumentUpload";
import { type AttachedDocument, type DocumentRequirement, attachDocument, toFileUrl } from "./submissionForm";

export interface GeneratedDocumentProps {
  path: string;
  requirement: DocumentRequirement;
  // Whether this reader may still change the request at all, which is the server's `editable`
  disabled: boolean;
  // The page's queue, which a preview and an upload wait in behind the answers being saved
  queueSave: Enqueue;
  onGenerated: () => void;
}

// The name an editable copy is saved under: the PDF's, as a .docx
const docxName = (title: string) => `${title.replace(/\.pdf$/i, "")}.docx`;

// One version of the document. A generated doc can be read in the browser, or downloaded as a .docx
// A submitter-uploaded document can only be downloaded as-is.
function Version({ document }: { document: AttachedDocument }) {
  if (!document.path) {
    return <>{document.title}</>;
  }
  if (!document.docx) {
    return (
      <>
        {"Your own version: "}
        <Link href={toFileUrl(document.path)} download={document.title}>{document.title}</Link>
      </>
    );
  }
  return (
    <>
      {"Generated from your answers: "}
      <Link href={toFileUrl(document.path)} target="_blank" rel="noopener">{document.title}</Link>
      {" · "}
      <Link href={toFileUrl(document.docx)} download={docxName(document.title)}>Download to edit (.docx)</Link>
    </>
  );
}

// A document the platform fills in from the answers when the request is sent. Until then the submitter can
// preview it, or upload a version of their own (e.g. an edited copy of a preview), which is sent instead.
function GeneratedDocument({ path, requirement, disabled, queueSave, onGenerated }: GeneratedDocumentProps) {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>(undefined);
  const doFetch = useAuthenticatedFetch();
  const versions = requirement.attached;
  const name = requirement.label || requirement.name;

  // Either way, the form is read again on success, since what is attached has changed. An upload is checked in
  // the browser first, as any other is, and only a file that passes waits in the queue.
  const send = (start: () => Promise<unknown>, check: Promise<string | undefined> = Promise.resolve(undefined)) => {
    setBusy(true);
    setFailure(undefined);
    check.then(problem => {
      if (problem !== undefined) {
        throw new Error(problem);
      }
      return queueSave(start);
    }).then(
      () => {
        setBusy(false);
        onGenerated();
      },
      (error: unknown) => {
        setBusy(false);
        // Why the browser check refused the file, or the engine's own reason, such as a template that does not
        // fit this schema
        setFailure(messageOf(error));
      }
    );
  };

  return (
    <Stack spacing={1} sx={{ alignItems: "flex-start" }}>
      {failure ? <Alert severity="error" onClose={() => setFailure(undefined)}>{failure}</Alert> : null}
      {versions.length > 0
        ? (
          <Typography variant="body2">
            {versions.map((document, index) => (
              <Fragment key={document.path ?? document.title}>
                {index > 0 ? "; " : null}
                <Version document={document} />
              </Fragment>
            ))}
          </Typography>
        )
        : <Typography variant="placeholder">Filled in from your answers when you send this request</Typography>}
      <Stack direction="row" spacing={1}>
        <Button
          size="small"
          variant="outlined"
          startIcon={<GenerateIcon />}
          disabled={disabled || busy}
          aria-label={`Preview "${name}"`}
          onClick={() => send(() => sendEvent(doFetch, path, "generateDocument", { requirement: requirement.name }))}
        >
          {versions.length > 0 ? "Preview again" : "Preview from my answers"}
        </Button>
        <FileButton
          label="Upload your own version"
          ariaLabel={`Upload your own version of "${name}"`}
          accepted={requirement.acceptedFileTypes}
          disabled={disabled || busy}
          onPick={file => send(() => attachDocument(doFetch, path, requirement.name, file),
            validateUpload(file, { accepted: requirement.acceptedFileTypes, timeoutMs: CHECK_TIMEOUT_MS }))}
        />
      </Stack>
    </Stack>
  );
}

export default GeneratedDocument;
