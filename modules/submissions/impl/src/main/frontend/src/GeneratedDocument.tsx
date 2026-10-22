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

import {
  type DocumentRequirement,
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

// A document the platform fills in from the answers. The submitter can read it but not replace it, so
// there is no upload control: opened in the browser rather than downloaded, since it is there to be read.
function GeneratedDocument({ path, requirement, disabled, onGenerated }: GeneratedDocumentProps) {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>(undefined);
  const doFetch = useAuthenticatedFetch();
  const generated = requirement.attached;
  const name = requirement.label || requirement.name;

  // Until submitting is a workflow step (IAP-88): the submitter asks for it here, from the answers so far
  const generate = () => {
    setBusy(true);
    setFailure(undefined);
    generateDocument(doFetch, path, requirement.name).then(
      () => {
        setBusy(false);
        onGenerated();
      },
      (error: unknown) => {
        setBusy(false);
        // The engine's own reason, such as a template that does not fit this schema
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
      {generated.length > 0
        ? (
          <Typography variant="body2">
            {"Generated from your answers: "}
            {generated.map((document, index) => (
              <Fragment key={document.path ?? document.title}>
                {index > 0 ? ", " : null}
                {document.path
                  ? <Link href={toFileUrl(document.path)} target="_blank" rel="noopener">{document.title}</Link>
                  : document.title}
              </Fragment>
            ))}
          </Typography>
        )
        : <Typography variant="placeholder">Generated from your answers once you ask for it</Typography>}
      <Button
        size="small"
        variant="outlined"
        startIcon={<GenerateIcon />}
        disabled={disabled || busy}
        aria-label={`${generated.length > 0 ? "Generate again" : "Generate"} "${name}"`}
        onClick={generate}
      >
        {generated.length > 0 ? "Generate again" : "Generate from my answers"}
      </Button>
    </Stack>
  );
}

export default GeneratedDocument;
