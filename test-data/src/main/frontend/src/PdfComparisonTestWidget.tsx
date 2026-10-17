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

import { Box, Button, Checkbox, FormControlLabel, Stack, TextField } from "@mui/material";

import PdfComparison, { type PdfComparisonProps } from "@iap/frontend-commons/pdfViewer/PdfComparison";

type Shown = Omit<PdfComparisonProps, "container" | "onClose"> & { inPlace: boolean };

// What a field holds, trimmed, or nothing when it is blank.
function getEntered(value: string): string | undefined {
  return value.trim().length > 0 ? value.trim() : undefined;
}

// A dashboard widget for trying PdfComparison by hand: point it at two reachable versions of a PDF and see
// what changed, without a real caller. Shown in place, the comparison takes a box below the form, from
// which it can be popped out. Compare shows what the form says then.
function PdfComparisonTestWidget() {
  const [olderUrl, setOlderUrl] = useState("");
  const [newerUrl, setNewerUrl] = useState("");
  const [olderLabel, setOlderLabel] = useState("");
  const [newerLabel, setNewerLabel] = useState("");
  const [inPlace, setInPlace] = useState(true);
  const [place, setPlace] = useState<HTMLDivElement | null>(null);
  const [shown, setShown] = useState<Shown | undefined>(undefined);

  const compare = () => setShown({
    olderUrl: olderUrl.trim(),
    newerUrl: newerUrl.trim(),
    olderLabel: getEntered(olderLabel),
    newerLabel: getEntered(newerLabel),
    inPlace,
  });

  return (
    <>
      <Stack spacing={2}>
        <TextField
          label="Older version URL"
          value={olderUrl}
          onChange={event => setOlderUrl(event.target.value)}
          placeholder="/path/to/v1.pdf"
          fullWidth
        />
        <TextField
          label="Newer version URL"
          value={newerUrl}
          onChange={event => setNewerUrl(event.target.value)}
          placeholder="/path/to/v2.pdf"
          fullWidth
        />
        <TextField
          label="Older version label (optional)"
          value={olderLabel}
          onChange={event => setOlderLabel(event.target.value)}
          fullWidth
        />
        <TextField
          label="Newer version label (optional)"
          value={newerLabel}
          onChange={event => setNewerLabel(event.target.value)}
          fullWidth
        />
        <FormControlLabel
          control={<Checkbox checked={inPlace} onChange={event => setInPlace(event.target.checked)} />}
          label="Show in place, below the form"
        />
        <Button
          variant="contained"
          fullWidth
          disabled={olderUrl.trim().length === 0 || newerUrl.trim().length === 0}
          onClick={compare}
        >
          Compare
        </Button>
      </Stack>
      <Box ref={setPlace} sx={{ display: shown?.inPlace ? "block" : "none", height: "70vh", mt: 2 }} />
      {shown !== undefined && (
        <PdfComparison
          olderUrl={shown.olderUrl}
          newerUrl={shown.newerUrl}
          olderLabel={shown.olderLabel}
          newerLabel={shown.newerLabel}
          container={shown.inPlace ? place : undefined}
          onClose={() => setShown(undefined)}
        />
      )}
    </>
  );
}

export default PdfComparisonTestWidget;
