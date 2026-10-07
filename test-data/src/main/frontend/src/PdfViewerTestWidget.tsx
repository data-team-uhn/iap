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

import { Button, Stack, TextField } from "@mui/material";

import PdfViewer from "@iap/frontend-commons/components/PdfViewer";

// A dashboard widget for trying PdfViewer by hand: point it at any reachable PDF and look for a
// quote in it, without a real caller. The titled frame comes from the dashboard.
function PdfViewerTestWidget() {
  const [url, setUrl] = useState("");
  const [quote, setQuote] = useState("");
  const [cite, setCite] = useState("");
  const [open, setOpen] = useState(false);

  return (
    <>
      <Stack spacing={2}>
        <TextField
          label="PDF URL"
          value={url}
          onChange={event => setUrl(event.target.value)}
          placeholder="/path/to/file.pdf#page=2"
          fullWidth
        />
        <TextField
          label="Quote to find"
          value={quote}
          onChange={event => setQuote(event.target.value)}
          multiline
          minRows={2}
          fullWidth
        />
        <TextField
          label="Citation (optional)"
          value={cite}
          onChange={event => setCite(event.target.value)}
          fullWidth
        />
        <Button
          variant="contained"
          fullWidth
          disabled={url.trim().length === 0 || quote.trim().length === 0}
          onClick={() => setOpen(true)}
        >
          Open
        </Button>
      </Stack>
      {open && (
        <PdfViewer
          passage={{
            quote: quote.trim(),
            source: url.trim(),
            cite: cite.trim().length > 0 ? cite.trim() : undefined,
          }}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}

export default PdfViewerTestWidget;
