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

import { Button, MenuItem, Stack, TextField } from "@mui/material";

import PdfViewer, { type PdfViewerProps } from "@iap/frontend-commons/pdfViewer/PdfViewer";

type Shown = Omit<PdfViewerProps, "onClose">;

// A dashboard widget for trying PdfViewer by hand: point it at any reachable PDF and look for a
// quote in it, without a real caller. The titled frame comes from the dashboard. Open shows what the
// form says then, so editing it does not reopen the document.
function PdfViewerTestWidget() {
  const [url, setUrl] = useState("");
  const [page, setPage] = useState("");
  const [quote, setQuote] = useState("");
  const [markedBy, setMarkedBy] = useState<PdfViewerProps["markedBy"]>("assistant");
  const [title, setTitle] = useState("");
  const [shown, setShown] = useState<Shown | undefined>(undefined);

  const open = () => setShown({
    url: url.trim(),
    page: Number(page) > 0 ? Number(page) : undefined,
    quote: quote.trim(),
    markedBy,
    title: title.trim().length > 0 ? title.trim() : undefined,
  });

  return (
    <>
      <Stack spacing={2}>
        <TextField
          label="PDF URL"
          value={url}
          onChange={event => setUrl(event.target.value)}
          placeholder="/path/to/file.pdf"
          fullWidth
        />
        <TextField
          label="Page (optional)"
          value={page}
          onChange={event => setPage(event.target.value)}
          slotProps={{ htmlInput: { inputMode: "numeric", pattern: "[0-9]*" } }}
          fullWidth
        />
        <TextField
          label="Text to find"
          value={quote}
          onChange={event => setQuote(event.target.value)}
          multiline
          minRows={2}
          fullWidth
        />
        <TextField
          select
          label="Marked by"
          value={markedBy}
          onChange={event => setMarkedBy(event.target.value as PdfViewerProps["markedBy"])}
          fullWidth
        >
          <MenuItem value="assistant">An AI assistant</MenuItem>
          <MenuItem value="annotation">A person</MenuItem>
        </TextField>
        <TextField
          label="Title (optional)"
          value={title}
          onChange={event => setTitle(event.target.value)}
          fullWidth
        />
        <Button
          variant="contained"
          fullWidth
          disabled={url.trim().length === 0 || quote.trim().length === 0}
          onClick={open}
        >
          Open
        </Button>
      </Stack>
      {shown !== undefined && (
        <PdfViewer
          url={shown.url}
          page={shown.page}
          quote={shown.quote}
          markedBy={shown.markedBy}
          title={shown.title}
          onClose={() => setShown(undefined)}
        />
      )}
    </>
  );
}

export default PdfViewerTestWidget;
