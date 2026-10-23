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
import { useId, useLayoutEffect, useRef, useState, type ReactNode, type Ref } from "react";

import ExpandLessIcon from "@mui/icons-material/ExpandLess";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { Box, ButtonBase, IconButton, Typography, useMediaQuery } from "@mui/material";
import { useTheme, type SxProps, type Theme } from "@mui/material/styles";

import { cutShort, failedColour, passageCard, passageDetails, passageLayer } from "./passageStyles";
import { capitalize, describePages, type MarkName, type PassageNote } from "./pdfViewerModel";
import { TEXT_ENTRY } from "../textEntry";
import { useScrollEdges } from "../useScrollEdges";

import type { MarkKind } from "./marksModel";

// Past this many lines, the passage is cut short until the person asks for all of it. On a phone it is
// cut to one line, sharing its row with the note and the button showing all of it.
const MAX_PASSAGE_LINES = 3;
const MAX_PASSAGE_LINES_ON_PHONE = 1;

// The same for what the caller shows under the passage, such as a long comment.
const MAX_DETAILS_LINES = 6;
const MAX_DETAILS_LINES_ON_PHONE = 3;

// The most of the viewer's height the card takes, scrolling past it, so the document's controls below it
// stay in reach.
const MAX_CARD_HEIGHT = "60%";

// The passage once known, and how the search for it went, on a card in its mark's colour. Once found, the
// passage and its note take the reader to it. A passage running past its limit of lines is cut short,
// with a button beside it showing all of it, since a button cannot hold another. One of several passages
// has controls for stepping through them above it, and the caller's details for the passage go under it,
// cut short too. The one button shows all of both, and so does moving the focus into what is cut. While
// what is under the passage holds a field to type in, such as a comment being written, the card stays open
// so the field is in reach.
export default function PassageCard({ ref, name, quote, note, markedBy, stepper, details, onShowPassage }: {
  ref?: Ref<HTMLDivElement>;
  // What the mark is called, naming the button showing all of it.
  name: MarkName;
  quote: string | undefined;
  note?: PassageNote;
  markedBy: MarkKind;
  stepper?: ReactNode;
  details?: ReactNode;
  onShowPassage: () => void;
}) {
  const phone = useMediaQuery(useTheme().breakpoints.down("sm"));
  const limit = phone ? MAX_PASSAGE_LINES_ON_PHONE : MAX_PASSAGE_LINES;
  const detailsLimit = phone ? MAX_DETAILS_LINES_ON_PHONE : MAX_DETAILS_LINES;
  const quoteId = useId();
  const [copy, setCopy] = useState<HTMLElement | null>(null);
  const [lines, setLines] = useState(0);
  const [expanded, setExpanded] = useState(false);
  const detailsId = useId();
  const detailsBox = useRef<HTMLDivElement>(null);
  const detailsEdges = useScrollEdges(detailsBox);
  const [holdsField, setHoldsField] = useState(false);
  const open = expanded || holdsField;
  const detailsCut = !open && detailsEdges.below;
  const hasDetails = details !== undefined;

  useLayoutEffect(() => {
    const box = detailsBox.current;
    if (box === null) {
      return undefined;
    }
    const check = () => setHoldsField(box.querySelector(TEXT_ENTRY) !== null);
    check();
    const changes = new MutationObserver(check);
    changes.observe(box, { childList: true, subtree: true });
    return () => changes.disconnect();
  }, [hasDetails]);

  // Once cut again, what is under the passage starts from its top, wherever it was scrolled to.
  useLayoutEffect(() => {
    if (!open && detailsBox.current !== null) {
      detailsBox.current.scrollTop = 0;
    }
  }, [open]);

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
  const cut = long && !open;
  const oneRow = phone && cut;
  const text = quote === undefined ? "" : `“${quote}”`;
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
            {text === "" ? "" : " — "}
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
      ref={ref}
      role="group"
      aria-label={capitalize(name.one)}
      sx={theme => ({
        ...passageCard(theme, markedBy),
        flexShrink: 0,
        maxHeight: MAX_CARD_HEIGHT,
        overflowX: "hidden",
        overflowY: "auto",
        overscrollBehavior: "contain",
      })}
    >
      {stepper}
      <Box
        sx={theme => ({
          position: "relative",
          display: "flex",
          // The copy measured below runs the whole passage's length, which the card would otherwise scroll to
          overflow: "clip",
          ...passageLayer(theme, markedBy),
        })}
      >
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
              aria-label={`Show the passage on ${describePages(note.foundOnPage, note.toPage)}`}
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
        {!holdsField && (long || detailsCut || expanded)
          ? (
            <IconButton
              aria-label={expanded ? `Show less of the ${name.one}` : `Show the whole ${name.one}`}
              aria-expanded={expanded}
              aria-controls={details === undefined ? quoteId : `${quoteId} ${detailsId}`}
              onClick={() => setExpanded(!expanded)}
              sx={{ alignSelf: oneRow ? "center" : "flex-end" }}
            >
              {expanded ? <ExpandLessIcon /> : <ExpandMoreIcon />}
            </IconButton>
          )
          : null}
      </Box>
      {details === undefined
        ? null
        : (
          <Box
            ref={detailsBox}
            id={detailsId}
            onFocus={() => {
              if (detailsCut) {
                setExpanded(true);
              }
            }}
            sx={passageDetails(detailsEdges, open ? undefined : detailsLimit)}
          >
            {details}
          </Box>
        )}
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
