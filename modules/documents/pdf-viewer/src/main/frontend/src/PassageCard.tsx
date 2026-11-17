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
// The card that shows one mark, and `NoteText`, the note on where its passage is.

import { useId, useLayoutEffect, useRef, useState, type ReactNode, type Ref } from "react";

import ExpandLessIcon from "@mui/icons-material/ExpandLess";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { Box, ButtonBase, IconButton, Typography } from "@mui/material";
import { visuallyHidden } from "@mui/utils";

import { TEXT_ENTRY } from "@iap/frontend-commons/textEntry";
import { usePhone } from "@iap/frontend-commons/usePhone";
import { useScrollEdges } from "@iap/frontend-commons/useScrollEdges";
import { capitalize } from "@iap/frontend-commons/words";

import { getCutShortStyle, getFailedColour, getPassageCardStyle, getPassageDetailsStyle, getPassageLayerStyle } from "./passageStyles";
import { describePages, type MarkName, type PassageNote } from "./pdfViewerModel";

import type { MarkKind } from "./marksModel";
import type { SxProps, Theme } from "@mui/material/styles";

// Past this many lines, the passage is cut short until the person asks for all of it. On a phone it is
// cut to one line, sharing its row with the note and the button showing all of it.
const MAX_PASSAGE_LINES = 3;
const MAX_PASSAGE_LINES_ON_PHONE = 1;

// The height kept for what stands in for a quote, such as a change's diff, in lines. It does not change
// with what the body holds, so stepping through the marks does not move what is under the card.
const BODY_LINES = 2;

// Past this many lines, what the caller shows under the passage, such as a long comment, is cut short. A
// phone shows fewer.
const MAX_DETAILS_LINES = 6;
const MAX_DETAILS_LINES_ON_PHONE = 3;

// The card's greatest height, as a share of what holds it. Past it the card scrolls, so the document keeps
// some room.
const MAX_CARD_HEIGHT = "60%";

function scrollToTop(element: HTMLElement): void {
  element.scrollTop = 0;
}

// The card showing one mark in its mark's colour: the passage or what stands in for it, and a note on how
// the search for it went. Once the passage is found, clicking it takes the reader there. A passage longer
// than its limit of lines is cut short, and so are the caller's details under it. One button shows all of
// both. It sits beside the passage, since the passage is itself a button and a button cannot hold another.
// Moving the focus into the cut details also shows them all. While the details hold a field to type in,
// such as a comment being written, the card stays open.
export default function PassageCard(props: {
  ref?: Ref<HTMLDivElement>;
  // What the mark is called, for the card's label and the button showing all of it.
  name: MarkName;
  quote: string | undefined;
  // What shows the mark in place of a quote, such as a change's diff.
  body?: ReactNode;
  // What the body says, for a screen reader. A button's label replaces what the button holds, so this is
  // given as the button's description.
  bodySaid?: string;
  // What to call the mark on the button taking the reader to it, such as "passage" or "change".
  what?: string;
  // How the search for the passage went, or where it was found.
  note?: PassageNote;
  // The kind of mark, which sets the card's colour.
  markedBy: MarkKind;
  // Controls for stepping through several marks, shown above the passage.
  stepper?: ReactNode;
  // What the caller shows under the passage, such as comments.
  details?: ReactNode;
  // Takes the reader to the passage in the document.
  onShowPassage: () => void;
}) {
  const { ref, name, quote, body, bodySaid, what = "passage", note, markedBy, stepper, details, onShowPassage } = props;
  const phone = usePhone();
  const limit = phone ? MAX_PASSAGE_LINES_ON_PHONE : MAX_PASSAGE_LINES;
  const detailsLimit = phone ? MAX_DETAILS_LINES_ON_PHONE : MAX_DETAILS_LINES;
  const quoteId = useId();
  const [copy, setCopy] = useState<HTMLElement | null>(null);
  const [lines, setLines] = useState(0);
  const [expanded, setExpanded] = useState(false);
  const detailsId = useId();
  const [detailsBox, setDetailsBox] = useState<HTMLDivElement | null>(null);
  const detailsEdges = useScrollEdges(detailsBox);
  const [holdsField, setHoldsField] = useState(false);
  const bodyBox = useRef<HTMLSpanElement>(null);
  const [bodyCut, setBodyCut] = useState(false);
  const open = expanded || holdsField;
  const detailsCut = !open && detailsEdges.below;

  // Whether the details hold a field to type in, checked again whenever they change.
  useLayoutEffect(() => {
    if (detailsBox === null) {
      return undefined;
    }
    const check = () => setHoldsField(detailsBox.querySelector(TEXT_ENTRY) !== null);
    check();
    const changes = new MutationObserver(check);
    changes.observe(detailsBox, { childList: true, subtree: true });
    return () => changes.disconnect();
  }, [detailsBox]);

  // Once the details are cut short again, they scroll back to their top.
  useLayoutEffect(() => {
    if (!open && detailsBox !== null) {
      scrollToTop(detailsBox);
    }
  }, [open, detailsBox]);

  // Whether the body runs past its lines, measured while it is held to them.
  useLayoutEffect(() => {
    const box = bodyBox.current;
    if (box === null || expanded) {
      return undefined;
    }
    const measure = () => setBodyCut(box.scrollHeight > box.clientHeight + 1);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(box);
    return () => observer.disconnect();
  }, [body, expanded]);

  // The passage's lines, counted on a hidden copy at the card's full width, which cutting the passage short
  // does not change.
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
  const text = quote === undefined || quote === "" ? "" : `“${quote}”`;
  const content = (
    <>
      {body === undefined
        ? null
        : (
          <Box
            ref={bodyBox}
            id={`${quoteId}-body`}
            component="span"
            sx={{
              display: "block",
              typography: "body2",
              ...expanded ? {} : { height: `${BODY_LINES}lh`, overflow: "hidden" },
              ...bodyCut && !expanded ? { maskImage: "linear-gradient(black 60%, transparent)" } : {},
            }}
          >
            <Box component="span" aria-hidden={bodySaid !== undefined}>{body}</Box>
          </Box>
        )}
      {body === undefined || bodySaid === undefined
        ? null
        : <Box component="span" id={`${quoteId}-said`} sx={visuallyHidden}>{bodySaid}</Box>}
      <Typography
        id={quoteId}
        variant="body2"
        component="span"
        sx={cut ? { ...getCutShortStyle(limit), ...(oneRow ? { flex: 1, minWidth: 0 } : {}) } : { display: "block" }}
      >
        {text}
        {long || note === undefined ? null : (
          <Typography component="span" variant="caption" aria-hidden>
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
            sx={{ display: "block", flexShrink: 0, pl: oneRow ? 1 : 0 }}
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
        ...getPassageCardStyle(theme, markedBy),
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
          // Without it, the card would scroll down the whole length of the hidden copy below.
          overflow: "clip",
          ...getPassageLayerStyle(theme, markedBy),
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
              aria-label={`Show the ${what} on ${describePages(note.foundOnPage, note.toPage)}`}
              aria-describedby={bodySaid === undefined ? quoteId : `${quoteId}-said`}
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
        {!holdsField && (long || bodyCut || detailsCut || expanded)
          ? (
            <IconButton
              aria-label={expanded ? `Show less of the ${name.one}` : `Show the whole ${name.one}`}
              aria-expanded={expanded}
              aria-controls={[
                body === undefined ? quoteId : `${quoteId}-body`,
                details === undefined ? "" : detailsId,
              ].filter(id => id !== "").join(" ")}
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
            ref={setDetailsBox}
            id={detailsId}
            onFocus={() => {
              if (detailsCut) {
                setExpanded(true);
              }
            }}
            sx={getPassageDetailsStyle(detailsEdges, open ? undefined : detailsLimit)}
          >
            {details}
          </Box>
        )}
    </Box>
  );
}

// The note on a passage, in full or `short`. A failed search is in the error colour. A page number looks
// like a link when it is not the page shown.
export function NoteText({ note, short = false }: { note: PassageNote; short?: boolean }) {
  if ("text" in note) {
    const text = short ? note.short : note.text;
    return note.failed ? <Box component="span" sx={getFailedColour}>{text}</Box> : text;
  }
  const pages = note.toPage === undefined ? `${note.foundOnPage}` : `${note.foundOnPage}–${note.toPage}`;
  return (
    <>
      {short ? "p. " : `${note.searched ? "found on " : ""}${note.toPage === undefined ? "page" : "pages"} `}
      <Box
        component="span"
        sx={note.shown ? undefined : { color: "primary.main", textDecoration: "underline" }}
      >
        {pages}
      </Box>
    </>
  );
}
