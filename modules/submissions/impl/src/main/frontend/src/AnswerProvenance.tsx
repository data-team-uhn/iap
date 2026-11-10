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

import { Box, Button, Link, Typography } from "@mui/material";

import PdfViewer from "@iap/frontend-commons/pdfViewer/PdfViewer";

import { getAssistantTint } from "./answers/answerFrame";
import {
  type ProvenancePassage,
  type ProvenanceStatus,
  type QuestionProvenance,
  canOpen,
  getBadge,
  getFurtherPassagesLabel,
  getLeadPassage,
  getOpenLabel,
  getTriggerLabel,
  isLowConfidence,
  parsePdfSource,
  showsConfidence,
} from "./provenance";

// Where the answer stands. Still to take reads as a plain label; the settled states keep a chip.
function Badge({ status }: { status: ProvenanceStatus }) {
  const settled = status !== "suggested";
  return (
    <Typography
      component="span"
      sx={{
        display: "inline-flex",
        alignItems: "center",
        borderRadius: 1,
        fontSize: "0.72rem",
        fontWeight: 500,
        whiteSpace: "nowrap",
        py: "2px",
        px: settled ? 1 : "4px",
        color: settled ? "text.secondary" : "assistant.main",
        backgroundColor: settled ? "action.hover" : getAssistantTint(12),
      }}
    >
      {getBadge(status)}
    </Typography>
  );
}

// The one warning here, so it is the only thing in the warning colour
function ConfidenceChip() {
  return (
    <Typography
      component="span"
      sx={{
        display: "inline-flex",
        alignItems: "center",
        px: 1,
        py: 0.25,
        borderRadius: 1,
        fontSize: "0.72rem",
        fontWeight: 500,
        whiteSpace: "nowrap",
        color: "warning.dark",
        backgroundColor: "warning.light",
      }}
    >
      Low confidence
    </Typography>
  );
}

// One quote as it stands in the document, with where it is and a way to open it there
function Quote({ passage, onOpen }: { passage: ProvenancePassage; onOpen: (passage: ProvenancePassage) => void }) {
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
      <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 1 }}>
        {passage.cite === undefined
          ? null
          : (
            <Typography component="span" sx={{ fontSize: "0.8em", color: "text.secondary" }}>
              {passage.cite}
            </Typography>
          )}
        {canOpen(passage) && (
          <Link component="button" type="button" onClick={() => onOpen(passage)} sx={{ fontSize: "0.78rem" }}>
            {getOpenLabel(passage)}
          </Link>
        )}
      </Box>
      <Typography
        sx={{
          fontSize: "0.82rem",
          lineHeight: 1.6,
          borderLeft: "4px solid",
          borderLeftColor: getAssistantTint(60),
          pl: 1.5,
        }}
      >
        {`“${passage.quote}”`}
      </Typography>
    </Box>
  );
}

// The quoted document, open at the quote, marked as the assistant's
function PassageViewer({ passage, source, onClose }: {
  passage: ProvenancePassage;
  source: string;
  onClose: () => void;
}) {
  const { url, page } = parsePdfSource(source);
  return (
    <PdfViewer
      url={url}
      page={page}
      marks={[ { id: "passage", kind: "assistant", quote: passage.quote, page } ]}
      title={passage.cite}
      onClose={onClose}
    />
  );
}

// Where a suggested answer came from: the badge, the quotes behind it, and the note that they do not
// support it. That note is about the reading only; it neither takes nor drops the answer.
export default function AnswerProvenance({ provenance, status, disabled = false, onRejectEvidence }: {
  provenance: QuestionProvenance;
  status: ProvenanceStatus;
  disabled?: boolean;
  onRejectEvidence: (rejected: boolean) => void;
}) {
  const [ open, setOpen ] = useState(false);
  const [ shown, setShown ] = useState<ProvenancePassage | undefined>(undefined);
  const passage = getLeadPassage(provenance);
  const further = getFurtherPassagesLabel(provenance);

  return (
    <Box
      sx={{
        mt: { xs: 0, md: 4 },
        pl: 1.5,
        minWidth: 0,
        borderLeft: "2px solid",
        borderLeftColor: status === "suggested" ? "assistant.main" : "divider",
        display: "flex",
        flexDirection: "column",
        gap: 0.75,
      }}
    >
      <Box sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Badge status={status} />
        {passage && (
          <Link
            component="button"
            type="button"
            onClick={() => setOpen(current => !current)}
            aria-expanded={open}
            sx={{
              fontSize: "0.8rem",
              textAlign: "left",
              maxWidth: "100%",
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
            }}
          >
            {getTriggerLabel(passage)}
          </Link>
        )}
        {further && (
          <Typography component="span" sx={{ fontSize: "0.72rem", color: "text.secondary" }}>{further}</Typography>
        )}
        {showsConfidence(provenance, status) && <ConfidenceChip />}
      </Box>

      {open && passage && (
        <Box
          sx={{
            display: "flex",
            flexDirection: "column",
            gap: 1,
            backgroundColor: getAssistantTint(15),
            border: "1px solid",
            borderColor: getAssistantTint(60),
            borderLeft: "3px solid",
            borderLeftColor: "assistant.main",
            borderRadius: 2,
            p: 1.5,
          }}
        >
          {provenance.passages.map((each, index) => (
            <Quote key={`${index}-${each.quote}`} passage={each} onOpen={setShown} />
          ))}
          {provenance.reasoning && (
            <Typography variant="description" sx={{ fontSize: "0.78rem" }}>{provenance.reasoning}</Typography>
          )}
          {/* One note for all the quotes, since the server keeps one flag per run */}
          <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 1.5 }}>
            {!disabled && provenance.evidenceRejected && (
              <Typography component="span" sx={{ fontSize: "0.78rem", color: "text.secondary" }}>
                Thanks, noted.{" "}
                <Link component="button" type="button" onClick={() => onRejectEvidence(false)}>Undo</Link>
              </Typography>
            )}
            {!disabled && !provenance.evidenceRejected && (
              <Link
                component="button"
                type="button"
                onClick={() => onRejectEvidence(true)}
                title="Tells us the reading quoted the wrong passage. Your answer is not changed."
                sx={{ fontSize: "0.78rem", color: "text.secondary" }}
              >
                This passage does not support the answer
              </Link>
            )}
            {isLowConfidence(provenance) && (
              <Typography component="span" sx={{ fontSize: "0.78rem", color: "warning.dark" }}>
                The document is not clear here. Your answer is what counts.
              </Typography>
            )}
          </Box>
        </Box>
      )}
      {shown?.source !== undefined && (
        <PassageViewer passage={shown} source={shown.source} onClose={() => setShown(undefined)} />
      )}
    </Box>
  );
}

// Takes the suggestion as it stands, by saving it as the answer. Shown only while it is still to take.
export function ConfirmAnswer({ status, disabled, onConfirm }: {
  status: ProvenanceStatus;
  disabled?: boolean;
  onConfirm: () => void;
}) {
  if (disabled || status !== "suggested") {
    return null;
  }
  return (
    <Button
      size="small"
      variant="outlined"
      color="assistant"
      onClick={onConfirm}
      title="Saves this answer as it stands."
      sx={{ textTransform: "none", fontWeight: 500, whiteSpace: "nowrap" }}
    >
      Confirm answer
    </Button>
  );
}
