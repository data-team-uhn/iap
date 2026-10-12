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
import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";

import CloseIcon from "@mui/icons-material/Close";
import CloseFullscreenIcon from "@mui/icons-material/CloseFullscreen";
import OpenInFullIcon from "@mui/icons-material/OpenInFull";
import { Box, DialogContent, IconButton, Typography } from "@mui/material";
import { visuallyHidden } from "@mui/utils";
import { createPortal } from "react-dom";

import MarkList from "./MarkList";
import { cannotMark, getMarksKey, orderMarks } from "./marksModel";
import PassageCard from "./PassageCard";
import PdfDocumentView from "./PdfDocumentView";
import PdfToolbar from "./PdfToolbar";
import { getFileName, getPassageNote, getPassageStatus } from "./pdfViewerModel";
import StepControls from "./StepControls";
import { useMarkPlaces, type MarkPlaces } from "./useMarkPlaces";
import { useNarrowViewer } from "./useNarrowViewer";
import { usePdfDocument } from "./usePdfDocument";
import { usePdfView } from "./usePdfView";
import LoadError from "../components/LoadError";
import ResponsiveDialog from "../components/ResponsiveDialog";

import type { Mark } from "./marksModel";

export interface PdfViewerProps {
  // The PDF to open.
  url: string;
  // The page to open it at: the selected mark's own page when absent, or else the first.
  page?: number;
  // The passages to find and mark, in the order to list them. Each is found from its own page onwards.
  // Marks equal to the last ones are not looked for again, even as other objects.
  marks: Mark[];
  // The mark selected, which the viewer takes the reader to once it is found, as it does each time the
  // caller selects another. When absent, or not among the marks, it is the first, in the order given,
  // not known to be missing from the document, until the person selects another.
  selectedMarkId?: string;
  // Told which mark the person selects, in the document or among the passages.
  onSelectMark?: (id: string) => void;
  // What the viewer is called; the PDF's file name when absent.
  title?: string;
  // An element for the viewer to fill, with a button to pop it out into a dialog. The element needs a
  // height of its own. Without a container, the viewer opens in a dialog. Null, as a ref is before
  // React attaches it, shows nothing until the element is there.
  container?: HTMLElement | null;
  onClose: () => void;
}

// Opens a PDF, finds the passages in it and marks them. The document still opens when a passage
// cannot be found. One passage is shown above the document; several are listed beside it, or, in a
// viewer too narrow for that, shown one at a time above it.
export default function PdfViewer(props: PdfViewerProps) {
  // Keyed so another document starts over with nothing left from the last one.
  return <PdfViewerSession key={`${props.url}\n${props.page}`} {...props} />;
}

// The document stays open here while the viewer moves between the container and the dialog.
function PdfViewerSession(props: PdfViewerProps) {
  const { url, page, selectedMarkId, onSelectMark, title, container, onClose } = props;
  const marksKey = getMarksKey(props.marks);
  const marks = useMemo(() => JSON.parse(marksKey) as Mark[], [marksKey]);
  const [chosenId, setChosenId] = useState<string | undefined>(undefined);
  // The mark last reported to the caller, whose selecting it in turn does not move the reader, and the
  // last the caller selected, which marks changing do not move the reader to again.
  const reportedId = useRef<string | undefined>(undefined);
  const followedId = useRef<string | undefined>(undefined);
  const { pdf, openError, retry } = usePdfDocument(url);
  const places = useMarkPlaces(pdf, marks);
  const firstMarkable = marks.find(mark => !cannotMark(places[mark.id]));
  const selectedId = [ selectedMarkId, chosenId ].find(id => marks.some(mark => mark.id === id))
    ?? (firstMarkable ?? marks.at(0))?.id;
  const select = useCallback((id: string) => {
    reportedId.current = id;
    setChosenId(id);
    onSelectMark?.(id);
  }, [onSelectMark]);
  const view = usePdfView(pdf, {
    page: page ?? marks.find(mark => mark.id === selectedId)?.page,
    marks,
    places,
    selectedId,
    onSelectMark: select,
  });
  const { showMark } = view;
  useEffect(() => {
    if (selectedMarkId === followedId.current || !marks.some(mark => mark.id === selectedMarkId)) {
      return;
    }
    followedId.current = selectedMarkId;
    if (selectedMarkId !== undefined && selectedMarkId !== reportedId.current) {
      showMark(selectedMarkId);
    }
  }, [selectedMarkId, marks, showMark]);
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
      marks={marks}
      places={places}
      selectedId={selectedId}
      onSelect={select}
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
  marks: Mark[];
  places: MarkPlaces;
  selectedId: string | undefined;
  onSelect: (id: string) => void;
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
  const { titleId, title, marks, places, selectedId, onSelect, openError, onRetry, view } = props;
  const { placeButton, edgeToEdge, onClose } = props;
  const { page, pageCount, setFrame, showPage, showMark, zoomIn, zoomOut, fitWidth } = view;
  const [body, setBody] = useState<HTMLElement | null>(null);
  const narrow = useNarrowViewer(body);
  const ordered = useMemo(() => orderMarks(marks, places), [marks, places]);
  const position = ordered.findIndex(mark => mark.id === selectedId);
  const selected = position < 0 ? undefined : ordered[position];
  const several = ordered.length > 1;
  const among = { index: position + 1, count: ordered.length };
  const status = { openError, page };
  const showSelected = (id: string) => {
    onSelect(id);
    showMark(id);
  };

  const card = selected === undefined || (several && !narrow)
    ? null
    : (
      <PassageCard
        key={selected.id}
        quote={selected.quote}
        note={getPassageNote(status, places[selected.id])}
        markedBy={selected.kind}
        stepper={several
          ? (
            <StepControls
              position={among.index}
              count={among.count}
              itemName="passage"
              onStep={next => showSelected(ordered[next - 1].id)}
            />
          )
          : undefined}
        onShowPassage={() => showMark(selected.id)}
      />
    );

  return (
    <Box ref={setBody} sx={{ display: "flex", flexDirection: "column", flex: "1 1 auto", minHeight: 0 }}>
      <Box sx={{ display: "flex", alignItems: "center", mb: 1 }}>
        <Typography id={titleId} variant="h6" component="h2" noWrap sx={{ flex: 1, minWidth: 0 }}>
          {title}
        </Typography>
        {placeButton}
        <IconButton aria-label="Close" edge="end" onClick={onClose}>
          <CloseIcon />
        </IconButton>
      </Box>
      {card}
      {/* Always rendered, even empty: a screen reader only announces changes to a status it already has. */}
      <Box role="status" sx={visuallyHidden}>
        {selected === undefined ? "" : getPassageStatus(status, places[selected.id], several ? among : undefined)}
      </Box>
      <Box sx={{ display: "flex", gap: 2, flex: "1 1 auto", minHeight: 0 }}>
        <Box sx={{ display: "flex", flexDirection: "column", flex: "1 1 auto", minWidth: 0 }}>
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
            : (
              <LoadError
                title="The document could not be opened"
                message={openError}
                onRetry={onRetry}
                sx={{ mb: 1 }}
              />
            )}
          <PdfDocumentView setFrame={setFrame} edgeToEdge={edgeToEdge} outlineSelected={several} />
        </Box>
        {several && !narrow
          ? (
            <MarkList
              marks={ordered}
              places={places}
              status={status}
              selectedId={selected?.id}
              onSelect={showSelected}
            />
          )
          : null}
      </Box>
    </Box>
  );
}
