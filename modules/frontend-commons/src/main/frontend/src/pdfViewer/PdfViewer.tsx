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
import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";

import CloseIcon from "@mui/icons-material/Close";
import CloseFullscreenIcon from "@mui/icons-material/CloseFullscreen";
import OpenInFullIcon from "@mui/icons-material/OpenInFull";
import { Box, DialogContent, IconButton, Typography } from "@mui/material";
import { createPortal } from "react-dom";

import PassageCard from "./PassageCard";
import PdfDocumentView from "./PdfDocumentView";
import PdfToolbar from "./PdfToolbar";
import { getFileName, getPassageNote, getPassageStatus } from "./pdfViewerModel";
import { useMarkPlaces } from "./useMarkPlaces";
import { usePdfDocument } from "./usePdfDocument";
import { usePdfView } from "./usePdfView";
import LoadError from "../components/LoadError";
import ResponsiveDialog from "../components/ResponsiveDialog";

import type { Mark, MarkKind, MarkPlace } from "./marksModel";

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

// The one mark the viewer draws: the passage.
const PASSAGE = "passage";

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
  const { pdf, openError, retry } = usePdfDocument(url);
  const marks = useMemo<Mark[]>(() => [ { id: PASSAGE, quote, page, kind: markedBy } ], [quote, page, markedBy]);
  const places = useMarkPlaces(pdf, marks);
  const view = usePdfView(pdf, { page, marks, places, selectedId: PASSAGE });
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
      place={places[PASSAGE] ?? { status: "searching" }}
      openError={openError}
      onRetry={retry}
      view={view}
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
  markedBy: MarkKind;
  place: MarkPlace;
  openError: string | undefined;
  onRetry: () => Promise<void>;
  view: ReturnType<typeof usePdfView>;
  // Moves the viewer between its container and the dialog, when it has a container.
  placeButton: ReactNode;
  // In the dialog on a phone, the document spans the full screen width, past the dialog's padding.
  edgeToEdge: boolean;
  onClose: () => void;
}

function ViewerBody(props: ViewerBodyProps) {
  const { titleId, title, quote, markedBy, place, openError, onRetry, view, placeButton, edgeToEdge, onClose } = props;
  const { page, pageCount, setFrame, showPage, showMark, zoomIn, zoomOut, fitWidth } = view;

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
      <PassageCard
        quote={quote}
        note={getPassageNote({ openError, page }, place)}
        status={getPassageStatus({ openError, page }, place)}
        markedBy={markedBy}
        onShowPassage={() => showMark(PASSAGE)}
      />
      <PdfToolbar
        page={page}
        pageCount={pageCount}
        onShowPage={showPage}
        onZoomIn={zoomIn}
        onZoomOut={zoomOut}
        onFitWidth={fitWidth}
      />
      {openError === undefined
        ? null
        : <LoadError title="The document could not be opened" message={openError} onRetry={onRetry} sx={{ mb: 1 }} />}
      <PdfDocumentView setFrame={setFrame} edgeToEdge={edgeToEdge} />
    </>
  );
}
