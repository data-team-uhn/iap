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

import { Box, ButtonBase, Typography } from "@mui/material";

import type { DraftedLetter } from "./rebLetter";

// The fonts of the letters reviewers send, as their templates set them, whatever the screen around them uses
const BODY_FONT = "Arial, Helvetica, sans-serif";
const LETTERHEAD_FONT = "\"Times New Roman\", Times, serif";

// A line of a paragraph, such as one of an address
const lines = (all: string[]) => all.map(line => <Box component="span" key={line} sx={{ display: "block" }}>{line}</Box>);

// PRETOTYPE. A letter to the study team as it will print: a white page in either colour scheme, as a document's are,
// in the template's fonts. Its points are the reviewer's issues, which a click selects, the one selected tinted.
export default function LetterPage({ letter, selected, onSelect }: {
  letter: DraftedLetter;
  selected?: string;
  onSelect?: (issue: string) => void;
}) {
  return (
    <Box sx={theme => ({ bgcolor: "common.white", color: "common.black", boxShadow: theme.shadows[2],
      // Its edge kept where it lies on white, as in the preview before sending
      border: 1, borderColor: "divider",
      maxWidth: 816, mx: "auto", px: { xs: 3, sm: 9 }, py: { xs: 3, sm: 6 }, fontFamily: BODY_FONT,
      "& p": { m: 0, mb: 2, fontSize: "0.95rem", lineHeight: 1.45 } })}>
      {/* The logo at the top left, as wide as the template has it on the page, the address across from it */}
      {letter.letterhead && (
        <Box sx={{ display: "flex", alignItems: "flex-start", gap: 2, mb: 3 }}>
          {letter.logo && <Box component="img" src={letter.logo} alt="" sx={{ width: "27%", minWidth: 120 }} />}
          <Box sx={{ flex: 1, textAlign: "right", fontFamily: LETTERHEAD_FONT, color: "grey.600", lineHeight: 1.3 }}>
            {lines(letter.letterhead)}
          </Box>
        </Box>
      )}
      <Box component="p" sx={{ textAlign: letter.letterhead ? "right" : undefined }}>{letter.date}</Box>
      <p>{letter.salutation}</p>
      <Box component="p" sx={{ fontWeight: "bold" }}>
        {lines(letter.re)}
      </Box>
      {letter.opening.map(paragraph => <p key={paragraph}>{paragraph}</p>)}
      {letter.sections.map(section => (
        <Box key={section.heading} component="section" sx={{ mb: 2.5 }}>
          <Box component="p" sx={{ fontWeight: "bold", textDecoration: "underline" }}>{section.heading}</Box>
          {section.points.map(point => (
            <ButtonBase
              key={point.id} id={`letter-point-${point.id}`} disabled={!onSelect} onClick={() => onSelect?.(point.id)}
              sx={theme => ({ display: "flex", alignItems: "flex-start", gap: 1, width: "100%", textAlign: "start",
                font: "inherit", fontSize: "0.95rem", lineHeight: 1.45, mb: 1.5, mx: -1, px: 1, py: 0.5,
                borderRadius: 1, scrollMarginTop: 24,
                bgcolor: point.id === selected ? theme.alpha(theme.palette.issue.main, 0.14) : undefined,
                "&:hover": { bgcolor: theme.alpha(theme.palette.issue.main, point.id === selected ? 0.18 : 0.06) } })}
            >
              <Box component="span" sx={{ flexShrink: 0, minWidth: 20 }}>{point.number}.</Box>
              <span>
                ({point.label}) {point.about && <i>{point.about}{/[?.!]$/.test(point.about) ? " " : ". "}</i>}
                {point.text}
                {point.quote && (
                  <Box component="span" sx={{ display: "block", mt: 0.5, color: "grey.700" }}>
                    The protocol says: &ldquo;{point.quote}&rdquo;
                  </Box>
                )}
                {point.citation && (
                  <Box component="span" sx={{ display: "block", mt: 0.5, color: "grey.700", fontStyle: "italic" }}>
                    Reference: {point.citation}
                  </Box>
                )}
              </span>
            </ButtonBase>
          ))}
        </Box>
      ))}
      {letter.closing.map(paragraph => <p key={paragraph}>{paragraph}</p>)}
      <p>{lines(letter.signature)}</p>
      {letter.signatories?.map(one => (
        <Box component="p" key={`${one.heading} ${one.lines[0]}`}>
          <Box component="span" sx={{ display: "block", fontStyle: "italic" }}>{one.heading}</Box>
          {lines(one.lines)}
        </Box>
      ))}
      {letter.footer && (
        <Typography sx={{ mt: 4, fontFamily: LETTERHEAD_FONT, fontSize: "0.75rem", color: "grey.600",
          textAlign: "center" }}>
          {letter.footer}
        </Typography>
      )}
    </Box>
  );
}
