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

import AutoAwesomeIcon from "@mui/icons-material/AutoAwesomeOutlined";
import CloseIcon from "@mui/icons-material/Close";
import { Button, IconButton, Popover, Stack, Tooltip, Typography } from "@mui/material";

import { type FormQuestion, describeValues } from "./submissionForm";

const TITLE = "What the newer document says";

// Offers what a newer version of the document says, beside the answer the submitter already gave.
// Taking it saves it as the answer; the old answer stays until then.
function NewerReadingSwitch({ question, values, disabled = false, onAccept }: {
  question: FormQuestion;
  values: string[];
  disabled?: boolean;
  onAccept: (values: string[]) => void;
}) {
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);

  return (
    <>
      <Tooltip title={TITLE}>
        {/* A disabled button fires no events, so the tooltip needs something that does */}
        <span>
          <IconButton
            size="small"
            aria-label={TITLE}
            disabled={disabled}
            onClick={event => setAnchor(event.currentTarget)}
            sx={{ color: "assistant.main" }}
          >
            <AutoAwesomeIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
      <Popover
        open={anchor !== null}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
      >
        <Stack spacing={1} sx={{ p: 2, maxWidth: 360, alignItems: "flex-start" }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "flex-start", alignSelf: "stretch" }}>
            <Typography variant="subtitle2" sx={{ flexGrow: 1 }}>{TITLE}</Typography>
            <IconButton size="small" aria-label="Close" onClick={() => setAnchor(null)} sx={{ mt: -1, mr: -1 }}>
              <CloseIcon fontSize="small" />
            </IconButton>
          </Stack>
          <Typography>{describeValues(question, values).join(", ")}</Typography>
          <Button
            size="small"
            variant="outlined"
            onClick={() => {
              setAnchor(null);
              onAccept(values);
            }}
          >
            Use this answer
          </Button>
        </Stack>
      </Popover>
    </>
  );
}

export default NewerReadingSwitch;
