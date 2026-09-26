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

import PdfViewer from "./pdfViewer";
import {
  type ProvenancePassage,
  type QuestionProvenance,
  badgeFor,
  canOpen,
  furtherPassagesLabel,
  isLowConfidence,
  leadPassage,
  openLabel,
  showsConfidence,
  statusOf,
  triggerLabel,
} from "./provenance";

// One hue for machine-suggested content, deliberately outside the severity family: an unreviewed
// suggestion is not a warning, and an accepted one is not a success. Held here rather than in the
// theme because nothing else in the app has a machine lane to colour.
const AI = "#6B4FBB";
const AI_TEXT = "#54399E";
const AI_TINT = "#F7F4FD";

interface AnswerProvenanceProps {
  provenance: QuestionProvenance;
  // The answer as it stands, which is what decides whether a settled suggestion still matches
  value: string[];
  disabled?: boolean;
  // Accepts the suggestion as it stands. Settles the answer without changing it.
  onAccept: () => void;
  // Says the cited passage does not support the answer. Deliberately does not settle anything.
  onRejectEvidence: (rejected: boolean) => void;
}

// The badge naming where the answer came from and how far the submitter has got with it. Unsettled
// reads as a plain label; the settled states keep a chip, so "still to check" and "done" stay
// legible at a glance without a legend.
function Badge({ status }: { status: ReturnType<typeof statusOf> }) {
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
        px: settled ? 1 : 0,
        p: "2px 4px",
        color: settled ? "text.secondary" : AI_TEXT,
        backgroundColor: settled ? "rgba(25,41,88,0.06)" : "rgb(25 41 88 / 8%)",
      }}
    >
      {badgeFor(status)}
    </Typography>
  );
}

// Low confidence is the one genuine advisory here, so it is the only thing that takes the warning
// colour. If high confidence were badged too, the amber would stop meaning anything.
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
        color: "#7A4A00",
        backgroundColor: "rgba(178,106,0,0.14)",
      }}
    >
      Low confidence
    </Typography>
  );
}

// One passage, exactly as the extraction quoted it and as it stands in the document, with where it
// lives and a way to open it there.
//
// Every passage is shown, not just the first. The server verifies each one against the document and
// sends the ones that held up, so a lane that showed one of three understated the evidence and left
// the other two as payload nobody could see.
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
            {openLabel(passage)}
          </Link>
        )}
      </Box>
      <Typography sx={{ fontSize: "0.82rem", lineHeight: 1.6, color: "text.primary", borderLeft: "4px solid rgba(107, 79, 187, 0.3)", paddingLeft: "12px" }}>
        {`“${passage.quote}”`}
      </Typography>
    </Box>
  );
}

// Where a pre-filled answer came from, and the two verdicts the submitter can give it.
//
// The two verdicts are independent on purpose. Accepting settles the answer. Saying the passage is
// wrong records that the extraction cited badly, and settles nothing: it is telemetry about the
// machine, not a step in the submitter's job, so nothing about it can read as required.
//
// Nothing opens itself. The excerpt in the trigger is usually enough to judge, so seeing the whole
// passage is always a deliberate reveal.
export default function AnswerProvenance(
  { provenance, value, onRejectEvidence }: AnswerProvenanceProps,
) {
  const [ open, setOpen ] = useState(false);
  const [ shown, setShown ] = useState<ProvenancePassage | undefined>(undefined);
  const status = statusOf(provenance, value);
  const passage = leadPassage(provenance);
  const further = furtherPassagesLabel(provenance);
  const pending = status === "suggested";

  return (
    <Box
      sx={{
        mt: { xs: 0, md: 7.3 },
        pl: 1.5,
        minWidth: 0,
        borderColor: pending ? AI : "rgba(25,41,88,0.14)",
        display: "flex",
        flexDirection: "column",
        gap: 0.75,
      }}
    >
      <Box sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Badge status={status} />
        {passage
          ? (
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
              {triggerLabel(passage)}
            </Link>
          )
          : null}
        {further === undefined
          ? null
          : (
            <Typography component="span" sx={{ fontSize: "0.72rem", color: "text.secondary" }}>
              {further}
            </Typography>
          )}
        {showsConfidence(provenance, value) ? <ConfidenceChip /> : null}
      </Box>

      {open && passage
        ? (
          <Box
            sx={{
              display: "flex",
              flexDirection: "column",
              gap: 1,
              backgroundColor: AI_TINT,
              border: "1px solid rgba(107,79,187,0.4)",
              borderLeft: `3px solid ${AI}`,
              borderRadius: 2,
              p: 1.5,
            }}
          >
            {provenance.passages.map((each, index) => (
              <Quote key={`${index}-${each.quote}`} passage={each} onOpen={setShown} />
            ))}
            {/* One verdict for the lane, not one per passage: the server records a single flag, and
                asking about each quote separately would ask more of a reader than the report is worth. */}
            <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 1.5 }}>
              {provenance.evidenceRejected
                ? (
                  <Typography component="span" sx={{ fontSize: "0.78rem", color: "text.secondary" }}>
                    Thanks, noted.{" "}
                    <Link component="button" type="button" onClick={() => onRejectEvidence(false)}>
                      Undo
                    </Link>
                  </Typography>
                )
                : (
                  <Link
                    component="button"
                    type="button"
                    onClick={() => onRejectEvidence(true)}
                    title="Tells us the extraction cited the wrong passage. Your answer is unaffected."
                    sx={{ fontSize: "0.78rem", color: "text.secondary" }}
                  >
                    This passage doesn{"’"}t support the answer
                  </Link>
                )}
              {isLowConfidence(provenance)
                ? (
                  <Typography component="span" sx={{ fontSize: "0.78rem", color: "#7A4A00" }}>
                    The protocol is ambiguous here {"—"} your answer is what counts.
                  </Typography>
                )
                : null}
            </Box>
          </Box>
        )
        : null}
      {shown?.source === undefined
        ? null
        : (
          <PdfViewer
            key={`${shown.source}:${shown.quote}`}
            passage={shown}
            onClose={() => setShown(undefined)}
          />
        )}
    </Box>
  );
}

// The button that accepts a drafted answer as it stands. Shown beside the answer itself, and only
// while the answer is the model's and may still be changed.
export function ConfirmAnswer(
  { provenance, value, disabled, onAccept }: Omit<AnswerProvenanceProps, "onRejectEvidence">,
) {
  if (disabled || statusOf(provenance, value) !== "suggested") {
    return null;
  }
  return (
    <Button
      size="small"
      variant="outlined"
      onClick={onAccept}
      title="Accepts this answer as it stands."
      sx={{ color: AI_TEXT, borderColor: AI, textTransform: "none", fontWeight: 500, whiteSpace: "nowrap" }}
    >
      Confirm answer
    </Button>
  );
}
