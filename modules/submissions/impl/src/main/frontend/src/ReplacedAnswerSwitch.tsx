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
import UndoIcon from "@mui/icons-material/UndoOutlined";
import { Button, IconButton, Popover, Stack, Tooltip, Typography } from "@mui/material";

import { type FormQuestion, describeValues } from "./submissionForm";

import type { ReplacementOffer } from "./provenance";

const TITLES: Record<ReplacementOffer["direction"], string> = {
  undo: "Your answer before the new version",
  redo: "What the new version says",
};

interface ReplacedAnswerSwitchProps {
  question: FormQuestion;
  offer: ReplacementOffer;
  disabled?: boolean;
  onAccept: (offer: ReplacementOffer) => void;
}

// Switches between the answer a newer version of the document replaced and what that version says.
function ReplacedAnswerSwitch({ question, offer, disabled = false, onAccept }: ReplacedAnswerSwitchProps) {
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  const title = TITLES[offer.direction];

  return (
    <>
      <Tooltip title={title}>
        {/* A disabled button fires no events, so the tooltip needs something that does */}
        <span>
          <IconButton
            size="small"
            aria-label={title}
            disabled={disabled}
            onClick={event => setAnchor(event.currentTarget)}
            sx={{ color: "assistant.main" }}
          >
            {offer.direction === "undo" ? <UndoIcon fontSize="small" /> : <AutoAwesomeIcon fontSize="small" />}
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
          <Typography variant="subtitle2">{title}</Typography>
          <Typography>{describeValues(question, offer.values).join(", ")}</Typography>
          <Button
            size="small"
            variant="outlined"
            onClick={() => {
              setAnchor(null);
              onAccept(offer);
            }}
          >
            Accept this answer
          </Button>
        </Stack>
      </Popover>
    </>
  );
}

export default ReplacedAnswerSwitch;
