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

import {
  type QuestionProvenance,
  badgeFor,
  isLowConfidence,
  leadPassage,
  openLabel,
  showsConfidence,
  splitQuote,
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
        py: 0.25,
        color: settled ? "text.secondary" : AI_TEXT,
        backgroundColor: settled ? "rgba(25,41,88,0.06)" : "transparent",
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

// The passage, with the words the extraction keyed on marked so the trigger line and the quote
// visibly refer to the same words.
function Quote({ provenance }: { provenance: QuestionProvenance }) {
  const passage = leadPassage(provenance);
  if (!passage) {
    return null;
  }
  const { before, span, after } = splitQuote(passage);
  return (
    <Typography sx={{ fontSize: "0.82rem", lineHeight: 1.6, color: "text.primary" }}>
      {"“"}
      {before}
      {span
        ? (
          <Box
            component="mark"
            sx={{
              borderRadius: "3px",
              px: "2px",
              fontStyle: "normal",
              backgroundColor: "rgba(107,79,187,0.16)",
              color: "#2E2350",
            }}
          >
            {span}
          </Box>
        )
        : null}
      {after}
      {"”"}
    </Typography>
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
  { provenance, value, disabled, onAccept, onRejectEvidence }: AnswerProvenanceProps,
) {
  const [ open, setOpen ] = useState(false);
  const status = statusOf(provenance, value);
  const passage = leadPassage(provenance);
  const pending = status === "suggested";

  return (
    <Box
      sx={{
        mt: 1,
        pl: 1.5,
        borderLeft: "3px solid",
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
        {passage?.cite === undefined
          ? null
          : (
            <Typography component="span" sx={{ fontSize: "0.72rem", color: "text.secondary" }}>
              {passage.cite}
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
            <Quote provenance={provenance} />
            <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 1.5 }}>
              <Link href="#" sx={{ fontSize: "0.78rem" }}>{openLabel(passage)}</Link>
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

      {pending && !disabled
        ? (
          <Box sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
            <Button
              size="small"
              variant="outlined"
              onClick={onAccept}
              sx={{ color: AI_TEXT, borderColor: AI, textTransform: "none", fontWeight: 500 }}
            >
              Looks right
            </Button>
            <Typography component="span" sx={{ fontSize: "0.75rem", color: "text.secondary" }}>
              Accepts this answer as it stands.
            </Typography>
          </Box>
        )
        : null}
    </Box>
  );
}
