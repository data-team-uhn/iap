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

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";

import { FileButton } from "./DocumentUpload";
import {
  type AttachedDocument,
  type DocumentRequirement,
  attachDocument,
  generateDocument,
  toFileUrl
} from "./submissionForm";

export interface GeneratedDocumentProps {
  path: string;
  requirement: DocumentRequirement;
  // Whether this reader may still change the request at all, which is the server's `editable`
  disabled: boolean;
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

// A document the platform fills in from the answers. The submitter may also replace it with a version of their
// own, (e.g. an edited copy), which becomes the newest version.
function GeneratedDocument({ path, requirement, disabled, onGenerated }: GeneratedDocumentProps) {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>(undefined);
  const doFetch = useAuthenticatedFetch();
  const versions = requirement.attached;
  const name = requirement.label || requirement.name;

  // Either way, the form is read again on success, since what is attached has changed
  const send = (request: Promise<void>) => {
    setBusy(true);
    setFailure(undefined);
    request.then(
      () => {
        setBusy(false);
        onGenerated();
      },
      (error: unknown) => {
        setBusy(false);
        // The engine's own reason, such as a template that does not fit this schema, or a type it does not allow
        setFailure(messageOf(error));
      }
    );
  };

  return (
    <Stack spacing={1} sx={{ alignItems: "flex-start" }}>
      {failure ? <Alert severity="error" onClose={() => setFailure(undefined)}>{failure}</Alert> : null}
      {requirement.outdated
        ? <Alert severity="warning">Your answers changed after this was generated.</Alert>
        : null}
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
        : <Typography variant="placeholder">Generated from your answers once you ask for it</Typography>}
      <Stack direction="row" spacing={1}>
        {/* Until submitting is a workflow step (IAP-88): the submitter asks for it here, from the answers so far */}
        <Button
          size="small"
          variant="outlined"
          startIcon={<GenerateIcon />}
          disabled={disabled || busy}
          aria-label={`${versions.length > 0 ? "Generate again" : "Generate"} "${name}"`}
          onClick={() => send(generateDocument(doFetch, path, requirement.name))}
        >
          {versions.length > 0 ? "Generate again" : "Generate from my answers"}
        </Button>
        <FileButton
          label="Upload your own version"
          ariaLabel={`Upload your own version of "${name}"`}
          accepted={requirement.acceptedFileTypes}
          disabled={disabled || busy}
          onPick={file => send(attachDocument(doFetch, path, requirement.name, file))}
        />
      </Stack>
    </Stack>
  );
}

export default GeneratedDocument;
