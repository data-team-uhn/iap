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
import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";

import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import CloseIcon from "@mui/icons-material/Close";
import CloseFullscreenIcon from "@mui/icons-material/CloseFullscreen";
import ExpandLessIcon from "@mui/icons-material/ExpandLess";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import FitWidthIcon from "@mui/icons-material/FitScreen";
import OpenInFullIcon from "@mui/icons-material/OpenInFull";
import ZoomInIcon from "@mui/icons-material/ZoomIn";
import ZoomOutIcon from "@mui/icons-material/ZoomOut";
import { Box, ButtonBase, DialogContent, IconButton, Typography, useMediaQuery } from "@mui/material";
import { useTheme, type SxProps, type Theme } from "@mui/material/styles";
import { visuallyHidden } from "@mui/utils";
import { createPortal } from "react-dom";

import { MARK_CLASS } from "./markLayer";
import { MARK_KINDS, type MarkKind } from "./marksModel";
import { getFileName, getPassageNote, getPassageStatus, type PassageNote } from "./pdfViewerModel";
import { unquote } from "./quoteMatch";
import { usePdfViewer } from "./usePdfViewer";
import LoadError from "../components/LoadError";
import ResponsiveDialog from "../components/ResponsiveDialog";

export interface PdfViewerProps {
  // The PDF to open.
  url: string;
  // The page to open it at, and to start looking for the quote from.
  page?: number;
  // The passage to find and highlight.
  quote: string;
  // Whose mark the passage carries: an AI assistant's, or a person's.
  markedBy: MarkKind;
  // What the viewer is called; the PDF's file name when absent.
  title?: string;
  // An element to show the viewer in, filling it, with a button to pop it out into a dialog. The
  // element needs a height of its own. Without one, the viewer opens in a dialog. Null, as a ref is
  // before React attaches it, shows nothing until the element is there.
  container?: HTMLElement | null;
  onClose: () => void;
}

// Enough page to read, even when a long quote takes most of the viewer.
const MIN_FRAME_HEIGHT = 200;

// Past this many lines, the passage is cut short until the person asks for all of it. On a phone it is
// cut to one line, sharing its row with the note and the button showing all of it.
const MAX_PASSAGE_LINES = 3;
const MAX_PASSAGE_LINES_ON_PHONE = 1;

// The error colour, in the shade that stays readable as small text on the passage's card in each scheme
function failedColour(theme: Theme) {
  const { error } = (theme.vars ?? theme).palette;
  return { color: error.dark, ...theme.applyStyles("dark", { color: `color-mix(in srgb, ${error.main}, white)` }) };
}

// Each kind of mark in its highlight colour, and in a deeper one when selected.
function markColours(theme: Theme) {
  const { highlight } = (theme.vars ?? theme).palette;
  return Object.fromEntries(MARK_KINDS.flatMap(kind => [
    [ `& .${MARK_CLASS}-${kind}`, { bgcolor: highlight[kind] } ],
    [ `& .${MARK_CLASS}-${kind}.selected`, { bgcolor: highlight[`${kind}Current`] } ],
  ]));
}

function cutShort(lines: number) {
  return { display: "-webkit-box", WebkitBoxOrient: "vertical", WebkitLineClamp: lines, overflow: "hidden" } as const;
}

// Opens a PDF, finds the quote in it and highlights it. The document still opens when the quote
// cannot be found.
export default function PdfViewer({ url, page, quote, markedBy, title, container, onClose }: PdfViewerProps) {
  // Keyed so another document or quote starts over with nothing left from the last one.
  return (
    <PdfViewerSession
      key={`${url}\n${page}\n${quote}`}
      url={url}
      page={page}
      quote={quote}
      markedBy={markedBy}
      title={title}
      container={container}
      onClose={onClose}
    />
  );
}

// The document stays open here while the viewer moves between the container and the dialog.
function PdfViewerSession({ url, page, quote, markedBy, title, container, onClose }: PdfViewerProps) {
  const viewer = usePdfViewer(url, page, quote, markedBy);
  const [poppedOut, setPoppedOut] = useState(false);
  const titleId = useId();
  const placeButtonRef = useRef<HTMLButtonElement>(null);
  // Set when popping back in, so focus goes to the button that pops out again rather than to nowhere.
  const poppingIn = useRef(false);
  useEffect(() => {
    if (!poppedOut && poppingIn.current) {
      poppingIn.current = false;
      placeButtonRef.current?.focus();
    }
  }, [poppedOut]);
  const popIn = () => {
    poppingIn.current = true;
    setPoppedOut(false);
  };
  if (container === null) {
    return null;
  }

  const inDialog = container === undefined || poppedOut;
  const placeButton = container === undefined
    ? null
    : (
      <IconButton
        ref={placeButtonRef}
        aria-label={poppedOut ? "Pop back in" : "Pop out"}
        onClick={poppedOut ? popIn : () => setPoppedOut(true)}
      >
        {poppedOut ? <CloseFullscreenIcon /> : <OpenInFullIcon />}
      </IconButton>
    );
  const body = (
    <ViewerBody
      titleId={titleId}
      title={title ?? getFileName(url)}
      quote={quote}
      markedBy={markedBy}
      viewer={viewer}
      placeButton={placeButton}
      edgeToEdge={inDialog}
      onClose={onClose}
    />
  );

  if (inDialog) {
    return (
      <ResponsiveDialog
        open
        width="lg"
        aria-labelledby={titleId}
        // Escape puts a popped-out viewer back rather than closing it.
        onClose={container !== undefined && poppedOut ? popIn : onClose}
        slotProps={{ paper: { sx: { height: "100%" } } }}
      >
        <DialogContent sx={{ display: "flex", flexDirection: "column", minHeight: 0, px: { xs: 2, sm: 3 } }}>
          {body}
        </DialogContent>
      </ResponsiveDialog>
    );
  }
  return createPortal(
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>{body}</Box>,
    container,
  );
}

interface ViewerBodyProps {
  titleId: string;
  title: string;
  quote: string;
  markedBy: PdfViewerProps["markedBy"];
  viewer: ReturnType<typeof usePdfViewer>;
  // Moves the viewer between its container and the dialog, when it has a container.
  placeButton: ReactNode;
  // In the dialog on a phone, the document spans the full screen width, past the dialog's padding.
  edgeToEdge: boolean;
  onClose: () => void;
}

function ViewerBody({ titleId, title, quote, markedBy, viewer, placeButton, edgeToEdge, onClose }: ViewerBodyProps) {
  const { state, place, setFrame, showPage, showPassage, zoomIn, zoomOut, fitWidth, retry } = viewer;
  const pageCount = state.pageCount ?? 0;
  // 0 until the document opens, which keeps both page buttons disabled.
  const shownPage = state.page ?? 0;
  const opened = state.pageCount !== undefined;

  return (
    <>
      <Box sx={{ display: "flex", alignItems: "center", mb: 1 }}>
        <Typography id={titleId} variant="h6" component="h2" noWrap sx={{ flex: 1, minWidth: 0 }}>
          {title}
        </Typography>
        {placeButton}
        <IconButton aria-label="Close" edge="end" onClick={onClose}>
          <CloseIcon />
        </IconButton>
      </Box>
      <PassageLine
        quote={quote}
        note={getPassageNote(state, place)}
        status={getPassageStatus(state, place)}
        markedBy={markedBy}
        onShowPassage={showPassage}
      />
      {/* One line even on a narrow phone: only the page label gives way. */}
      <Box sx={{ display: "flex", alignItems: "center", mb: 1 }}>
        <IconButton
          aria-label="Previous page"
          edge="start"
          disabled={shownPage <= 1}
          onClick={() => showPage(shownPage - 1)}
        >
          <ChevronLeftIcon />
        </IconButton>
        <Typography variant="caption" noWrap sx={{ minWidth: 0, mx: 0.5 }}>
          {opened ? `Page ${shownPage} of ${pageCount}` : "Page"}
        </Typography>
        <IconButton aria-label="Next page" disabled={shownPage >= pageCount} onClick={() => showPage(shownPage + 1)}>
          <ChevronRightIcon />
        </IconButton>
        <Box sx={{ flexGrow: 1 }} />
        <IconButton aria-label="Zoom out" disabled={!opened} onClick={zoomOut}>
          <ZoomOutIcon />
        </IconButton>
        <IconButton aria-label="Fit to width" disabled={!opened} onClick={fitWidth}>
          <FitWidthIcon />
        </IconButton>
        <IconButton aria-label="Zoom in" edge="end" disabled={!opened} onClick={zoomIn}>
          <ZoomInIcon />
        </IconButton>
      </Box>
      {state.openError === undefined
        ? null
        : (
          <LoadError
            title="The document could not be opened"
            message={state.openError}
            onRetry={retry}
            sx={{ mb: 1 }}
          />
        )}
      {/* PDF.js needs its scrolling container absolutely positioned, so this box gives it the room. */}
      <Box
        sx={{
          position: "relative",
          flex: "1 1 auto",
          minHeight: MIN_FRAME_HEIGHT,
          mx: edgeToEdge ? { xs: -2, sm: 0 } : 0,
        }}
      >
        <Box
          ref={setFrame}
          role="region"
          aria-label="Document"
          tabIndex={0}
          sx={theme => ({
            position: "absolute",
            inset: 0,
            overflow: "auto",
            bgcolor: "background.default",
            borderRadius: edgeToEdge ? { xs: 0, sm: 1 } : 1,
            // Sheets on the canvas, apart from each other
            "& .pdfViewer.removePageBorders .page": { my: 1.5, mx: "auto", boxShadow: 2 },
            // Multiplied like a highlighter's ink, so marks tint the paper and leave the text as dark.
            "& .textLayer": { mixBlendMode: "multiply" },
            [`& .${MARK_CLASS}`]: { borderRadius: 0.5 },
            ...markColours(theme),
          })}
        >
          <div className="pdfViewer" />
        </Box>
      </Box>
    </>
  );
}

// The passage and how the search for it went, on a card in its mark's colour. Once it is found, the
// passage and its note take the reader to it. A passage running past its limit of lines is cut short,
// with a button beside it showing all of it, since a button cannot hold another. On a phone, cut to one
// line, it shares its row with the note.
function PassageLine({ quote, note, status, markedBy, onShowPassage }: {
  quote: string;
  note?: PassageNote;
  status: string;
  markedBy: PdfViewerProps["markedBy"];
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
      sx={theme => ({
        position: "relative",
        display: "flex",
        mb: 1,
        borderRadius: 1,
        overflow: "hidden",
        borderInlineStart: `${theme.spacing(0.5)} solid`,
        borderColor: `${markedBy}.main`,
        bgcolor: theme.alpha((theme.vars ?? theme).palette[markedBy].light, 0.25),
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
      {/* Always rendered, even empty: a screen reader only announces changes to a status it already has. */}
      <Box role="status" sx={visuallyHidden}>{status}</Box>
    </Box>
  );
}

// The page a passage was found on is styled as a link when it is not the page shown, as the passage
// and its note take the reader there.
function NoteText({ note, short = false }: { note: PassageNote; short?: boolean }) {
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
