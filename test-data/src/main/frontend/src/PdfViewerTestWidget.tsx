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

import { Box, Button, Checkbox, FormControlLabel, MenuItem, Stack, TextField } from "@mui/material";

import { MARK_KINDS, type MarkKind } from "@iap/frontend-commons/pdfViewer/marksModel";
import PdfViewer, { type PdfViewerProps } from "@iap/frontend-commons/pdfViewer/PdfViewer";

type Shown = Omit<PdfViewerProps, "container" | "onClose"> & { inPlace: boolean };

// Whose marks the passages are: one kind for all, or both in turn.
type MarkedBy = MarkKind | "both";

// A dashboard widget for trying PdfViewer by hand on any reachable PDF, with passages to look for given
// one per line. Open shows what the form says at that moment, so editing the form afterwards does not
// reopen the document.
function PdfViewerTestWidget() {
  const [url, setUrl] = useState("");
  const [page, setPage] = useState("");
  const [quotes, setQuotes] = useState("");
  const [markedBy, setMarkedBy] = useState<MarkedBy>("assistant");
  const [title, setTitle] = useState("");
  const [inPlace, setInPlace] = useState(true);
  const [place, setPlace] = useState<HTMLDivElement | null>(null);
  const [shown, setShown] = useState<Shown | undefined>(undefined);

  const lines = quotes.split("\n").map(line => line.trim()).filter(line => line.length > 0);

  const open = () => {
    const firstPage = Number(page) > 0 ? Number(page) : undefined;
    setShown({
      url: url.trim(),
      page: firstPage,
      marks: lines.map((quote, index) => ({
        id: `passage-${index + 1}`,
        quote,
        page: firstPage,
        kind: markedBy === "both" ? MARK_KINDS[index % MARK_KINDS.length] : markedBy,
      })),
      title: title.trim().length > 0 ? title.trim() : undefined,
      inPlace,
    });
  };

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
          helperText="One passage per line"
          value={quotes}
          onChange={event => setQuotes(event.target.value)}
          multiline
          minRows={2}
          fullWidth
        />
        <TextField
          select
          label="Marked by"
          value={markedBy}
          onChange={event => setMarkedBy(event.target.value as MarkedBy)}
          fullWidth
        >
          <MenuItem value="assistant">An AI assistant</MenuItem>
          <MenuItem value="annotation">A person</MenuItem>
          <MenuItem value="both">Both, in turn</MenuItem>
        </TextField>
        <TextField
          label="Title (optional)"
          value={title}
          onChange={event => setTitle(event.target.value)}
          fullWidth
        />
        <FormControlLabel
          control={<Checkbox checked={inPlace} onChange={event => setInPlace(event.target.checked)} />}
          label="Show in place, below the form"
        />
        <Button
          variant="contained"
          fullWidth
          disabled={url.trim().length === 0 || lines.length === 0}
          onClick={open}
        >
          Open
        </Button>
      </Stack>
      <Box ref={setPlace} sx={{ display: shown?.inPlace ? "block" : "none", height: "70vh", mt: 2 }} />
      {shown !== undefined && (
        <PdfViewer
          url={shown.url}
          page={shown.page}
          marks={shown.marks}
          title={shown.title}
          container={shown.inPlace ? place : undefined}
          onClose={() => setShown(undefined)}
        />
      )}
    </>
  );
}

export default PdfViewerTestWidget;
