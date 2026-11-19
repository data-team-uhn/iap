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

import { type ReactNode, useState } from "react";

import DownloadIcon from "@mui/icons-material/Download";
import { Box, Button, DialogActions, DialogContent, Stack, Typography } from "@mui/material";

import PdfViewer from "@iap/documents-pdf-viewer/PdfViewer";
import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";

import { type GeneratedVersion } from "./routeModel";
import { toFileUrl } from "./submissionForm";

// The name the editable copy is saved under: the PDF's, as a .docx
function getDocxName(title: string): string {
  return `${title.replace(/\.pdf$/i, "")}.docx`;
}

// The document the platform just wrote from the answers, to read, download, and send or keep for
// later. `sendControls` are the request's own send buttons.
function ProposalDialog({ document, sendControls, onClose }: {
  document: GeneratedVersion;
  sendControls: ReactNode;
  onClose: () => void;
}) {
  // The viewer draws into this element once React has attached it
  const [ frame, setFrame ] = useState<HTMLDivElement | null>(null);

  return (
    <ResponsiveDialog open width="lg" title="Your proposal" withCloseButton onClose={onClose}>
      <DialogContent dividers>
        <Box ref={setFrame} sx={{ height: "65vh" }} />
        <PdfViewer url={toFileUrl(document.path)} title={document.title} marks={[]} container={frame}
          onClose={onClose} />
      </DialogContent>
      <DialogActions sx={{ flexWrap: "wrap", gap: 1 }}>
        <Typography variant="description" sx={{ flexGrow: 1 }}>
          It is saved with your request. Send it for review now, or come back to it later.
        </Typography>
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
          <Button startIcon={<DownloadIcon />} href={toFileUrl(document.path)} download={document.title}>PDF</Button>
          <Button startIcon={<DownloadIcon />} href={toFileUrl(document.docx)} download={getDocxName(document.title)}>
            Word
          </Button>
          <Button onClick={onClose}>Keep editing</Button>
          {sendControls}
        </Stack>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default ProposalDialog;
