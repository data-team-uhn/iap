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
import { useId, useLayoutEffect, useState, type ReactNode } from "react";

import ExpandLessIcon from "@mui/icons-material/ExpandLess";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { Box, ButtonBase, IconButton, Typography, useMediaQuery } from "@mui/material";
import { useTheme, type SxProps, type Theme } from "@mui/material/styles";

import { cutShort, failedColour, passageCard } from "./passageStyles";
import { unquote } from "./quoteMatch";

import type { MarkKind } from "./marksModel";
import type { PassageNote } from "./pdfViewerModel";

// Past this many lines, the passage is cut short until the person asks for all of it. On a phone it is
// cut to one line, sharing its row with the note and the button showing all of it.
const MAX_PASSAGE_LINES = 3;
const MAX_PASSAGE_LINES_ON_PHONE = 1;

// The passage and how the search for it went, on a card in its mark's colour. Once it is found, the
// passage and its note take the reader to it. A passage running past its limit of lines is cut short,
// with a button beside it showing all of it, since a button cannot hold another. On a phone, cut to one
// line, it shares its row with the note. One of several passages has controls for stepping through
// them above it.
export default function PassageCard({ quote, note, markedBy, stepper, onShowPassage }: {
  quote: string;
  note?: PassageNote;
  markedBy: MarkKind;
  stepper?: ReactNode;
  onShowPassage: () => void;
}) {
  const phone = useMediaQuery(useTheme().breakpoints.down("sm"));
  const limit = phone ? MAX_PASSAGE_LINES_ON_PHONE : MAX_PASSAGE_LINES;
  const quoteId = useId();
  const [copy, setCopy] = useState<HTMLElement | null>(null);
  const [lines, setLines] = useState(0);
  const [expanded, setExpanded] = useState(false);

  // Counted on an invisible copy at the card's full width, which cutting the passage short does not change.
  useLayoutEffect(() => {
    if (copy === null) {
      return undefined;
    }
    const measure = () => setLines(copy.firstElementChild?.getClientRects().length ?? 0);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(copy);
    return () => observer.disconnect();
  }, [copy]);

  const long = lines > limit;
  const cut = long && !expanded;
  const oneRow = phone && cut;
  const text = `“${unquote(quote)}”`;
  const content = (
    <>
      <Typography
        id={quoteId}
        variant="body2"
        component="span"
        sx={cut ? { ...cutShort(limit), ...(oneRow ? { flex: 1, minWidth: 0 } : {}) } : { display: "block" }}
      >
        {text}
        {long || note === undefined ? null : (
          <Typography component="span" variant="caption" aria-hidden sx={{ color: "text.secondary" }}>
            {" — "}
            <NoteText note={note} />
          </Typography>
        )}
      </Typography>
      {long && note !== undefined
        ? (
          <Typography
            component="span"
            variant="caption"
            aria-hidden
            sx={{ display: "block", flexShrink: 0, pl: oneRow ? 1 : 0, color: "text.secondary" }}
          >
            <NoteText note={note} short={oneRow} />
          </Typography>
        )
        : null}
    </>
  );
  const passageSx: SxProps<Theme> = {
    flex: 1,
    minWidth: 0,
    minHeight: 40,
    px: 1.5,
    py: 1,
    display: "flex",
    flexDirection: oneRow ? "row" : "column",
    alignItems: oneRow ? "center" : "stretch",
    justifyContent: "center",
    textAlign: "start",
  };

  return (
    <Box
      sx={theme => ({ ...passageCard(theme, markedBy), mb: 1, overflow: "hidden" })}
    >
      {stepper}
      <Box sx={{ position: "relative", display: "flex" }}>
        <Typography
          ref={setCopy}
          variant="body2"
          component="span"
          aria-hidden
          sx={{ position: "absolute", insetInline: 0, px: 1.5, visibility: "hidden", pointerEvents: "none" }}
        >
          <span>{text}</span>
        </Typography>
        {note !== undefined && "foundOnPage" in note
          ? (
            <ButtonBase
              focusRipple
              aria-label={note.toPage === undefined
                ? `Show the passage on page ${note.foundOnPage}`
                : `Show the passage on pages ${note.foundOnPage} and ${note.toPage}`}
              aria-describedby={quoteId}
              onClick={onShowPassage}
              sx={{
                ...passageSx,
                "@media (hover: hover)": { "&:hover": { bgcolor: "action.hover" } },
                "&.Mui-focusVisible": { bgcolor: "action.hover" },
              }}
            >
              {content}
            </ButtonBase>
          )
          : <Box sx={passageSx}>{content}</Box>}
        {long
          ? (
            <IconButton
              aria-label={expanded ? "Show less of the passage" : "Show the whole passage"}
              aria-expanded={expanded}
              aria-controls={quoteId}
              onClick={() => setExpanded(!expanded)}
              sx={{ alignSelf: oneRow ? "center" : "flex-end" }}
            >
              {expanded ? <ExpandLessIcon /> : <ExpandMoreIcon />}
            </IconButton>
          )
          : null}
      </Box>
    </Box>
  );
}

// When the passage was found on a page other than the one shown, the page number is styled as a link.
// The passage and its note take the reader there.
export function NoteText({ note, short = false }: { note: PassageNote; short?: boolean }) {
  if ("text" in note) {
    const text = short ? note.short : note.text;
    return note.failed ? <Box component="span" sx={failedColour}>{text}</Box> : text;
  }
  const pages = note.toPage === undefined ? `${note.foundOnPage}` : `${note.foundOnPage}–${note.toPage}`;
  return (
    <>
      {short ? "p. " : `found on ${note.toPage === undefined ? "page" : "pages"} `}
      <Box
        component="span"
        sx={note.shown ? undefined : { color: "primary.main", textDecoration: "underline" }}
      >
        {pages}
      </Box>
    </>
  );
}
