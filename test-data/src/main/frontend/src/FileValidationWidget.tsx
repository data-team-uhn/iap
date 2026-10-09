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

import { type ChangeEvent, useEffect, useState } from "react";

import UploadIcon from "@mui/icons-material/Upload";
import { Alert, Box, Button, CircularProgress, MenuItem, Stack, TextField, Typography } from "@mui/material";
import { visuallyHidden } from "@mui/utils";

import {
  MAX_FILE_SIZE,
  MAX_PDF_PAGES,
  MAX_UNZIPPED_SIZE,
  MEGABYTE,
  MIME_TYPE_BY_EXTENSION,
  PIPELINE_TYPES,
  validateUpload,
} from "@iap/frontend-commons/fileValidation";

const ACCEPTS: Record<string, { label: string; types: string[] }> = {
  anything: { label: "Anything", types: [] },
  pipeline: { label: "What the pipeline reads", types: PIPELINE_TYPES },
  pdf: { label: "PDF only", types: [ MIME_TYPE_BY_EXTENSION[".pdf"] ] },
  word: { label: "Word only", types: [ MIME_TYPE_BY_EXTENSION[".docx"], MIME_TYPE_BY_EXTENSION[".doc"] ] },
};

// How long the settings have to stay unchanged before the file is checked
const CHECK_DELAY_MS = 300;

// What one check was asked, and what it answered
interface Check {
  file: File;
  accepts: string;
  maxMegabytes: string;
  maxPages: string;
  maxUnzipped: string;
  problem?: string;
}

// A positive number, or undefined so the check falls back to its default.
function getLimit(value: string): number | undefined {
  const number = Number(value);
  return value !== "" && number > 0 ? number : undefined;
}

// A limit typed in megabytes, in bytes.
function getByteLimit(value: string): number | undefined {
  const megabytes = getLimit(value);
  return megabytes === undefined ? undefined : megabytes * MEGABYTE;
}

// A dashboard toy for the upload check. Pick any file and see what it would be refused for, under the
// types and limits set here, without attaching it to anything. Smaller limits make the size, page and
// unzipped-size refusals easy to reach. Shown on the homepage when the app is started with --test.
function FileValidationWidget() {
  const [file, setFile] = useState<File | undefined>(undefined);
  const [accepts, setAccepts] = useState("anything");
  const [maxMegabytes, setMaxMegabytes] = useState(String(MAX_FILE_SIZE / MEGABYTE));
  const [maxPages, setMaxPages] = useState(String(MAX_PDF_PAGES));
  const [maxUnzipped, setMaxUnzipped] = useState(String(MAX_UNZIPPED_SIZE / MEGABYTE));
  const [answer, setAnswer] = useState<Check | undefined>(undefined);

  // Checked again whenever a setting changes, so the same file can be tried against each one. The
  // check waits for typing to stop: each one reads the whole file, and a PDF gets its own worker.
  useEffect(() => {
    if (!file) {
      return undefined;
    }
    let current = true;
    const asked = { file, accepts, maxMegabytes, maxPages, maxUnzipped };
    // A check the settings have moved on from is stopped, not only ignored
    const controller = new AbortController();
    const timer = setTimeout(() => {
      validateUpload(file, ACCEPTS[accepts].types, {
        maxFileSize: getByteLimit(maxMegabytes),
        maxPdfPages: getLimit(maxPages),
        maxUnzippedSize: getByteLimit(maxUnzipped),
      }, controller.signal).then(
        problem => {
          if (current) {
            setAnswer({ ...asked, problem });
          }
        },
        (error: unknown) => {
          if (current) {
            setAnswer({ ...asked, problem: String(error) });
          }
        }
      );
    }, CHECK_DELAY_MS);
    return () => {
      current = false;
      clearTimeout(timer);
      controller.abort();
    };
  }, [file, accepts, maxMegabytes, maxPages, maxUnzipped]);

  // An answer to other settings is stale, so the check for these ones is still underway
  const shown = answer && answer.file === file && answer.accepts === accepts
    && answer.maxMegabytes === maxMegabytes && answer.maxPages === maxPages
    && answer.maxUnzipped === maxUnzipped ? answer : undefined;

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap", alignItems: "center" }}>
        <Button component="label" variant="outlined" startIcon={<UploadIcon />}>
          Pick a file
          <Box
            component="input"
            type="file"
            sx={visuallyHidden}
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              setFile(event.target.files?.[0]);
              // Cleared so that picking the same file again is still a change
              event.target.value = "";
            }}
          />
        </Button>
        <TextField
          select
          size="small"
          label="Accepts"
          value={accepts}
          onChange={event => setAccepts(event.target.value)}
          sx={{ minWidth: 200 }}
        >
          {Object.entries(ACCEPTS).map(([key, option]) => <MenuItem key={key} value={key}>{option.label}</MenuItem>)}
        </TextField>
        <TextField
          size="small"
          type="number"
          label="Size limit (MB)"
          value={maxMegabytes}
          onChange={event => setMaxMegabytes(event.target.value)}
          sx={{ width: 140 }}
        />
        <TextField
          size="small"
          type="number"
          label="Page limit"
          value={maxPages}
          onChange={event => setMaxPages(event.target.value)}
          sx={{ width: 120 }}
        />
        <TextField
          size="small"
          type="number"
          label="Unzip limit (MB)"
          value={maxUnzipped}
          onChange={event => setMaxUnzipped(event.target.value)}
          sx={{ width: 140 }}
        />
      </Stack>
      {!file
        ? <Typography variant="placeholder">No file picked yet</Typography>
        : !shown
          ? (
            <Stack direction="row" spacing={1} role="status" sx={{ alignItems: "center" }}>
              <CircularProgress size={16} />
              <Typography variant="body2">{`Checking ${file.name}…`}</Typography>
            </Stack>
          )
          : shown.problem === undefined
            ? <Alert severity="success">{`${file.name} passes every check.`}</Alert>
            : <Alert severity="error">{shown.problem}</Alert>}
    </Stack>
  );
}

export default FileValidationWidget;
