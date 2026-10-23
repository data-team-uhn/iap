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
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

import CloseIcon from "@mui/icons-material/Close";
import CloseFullscreenIcon from "@mui/icons-material/CloseFullscreen";
import OpenInFullIcon from "@mui/icons-material/OpenInFull";
import SearchIcon from "@mui/icons-material/Search";
import { Box, DialogContent, IconButton, Typography } from "@mui/material";
import { visuallyHidden } from "@mui/utils";
import { createPortal } from "react-dom";

import CommentButton from "./CommentButton";
import MarkList from "./MarkList";
import { cannotMark, getMarkPage, getMarksKey, getMarkText, orderMarks } from "./marksModel";
import PassageCard from "./PassageCard";
import PdfDocumentView from "./PdfDocumentView";
import PdfToolbar from "./PdfToolbar";
import { getFileName, getPassageNote, getPassageStatus } from "./pdfViewerModel";
import SearchField from "./SearchField";
import StepControls from "./StepControls";
import { getTextAnchor } from "./textAnchor";
import { useMarkPlaces, type MarkPlaces } from "./useMarkPlaces";
import { useNarrowerThan, useNarrowViewer } from "./useNarrowViewer";
import { usePdfDocument } from "./usePdfDocument";
import { usePdfView } from "./usePdfView";
import { useSelectedText } from "./useSelectedText";
import LoadError from "../components/LoadError";
import ResponsiveDialog from "../components/ResponsiveDialog";

import type { Mark, MarkSpan, TextAnchor } from "./marksModel";

export interface PdfViewerProps {
  // The PDF to open.
  url: string;
  // The page to open it at: the selected mark's own page when absent, or else the first.
  page?: number;
  // The passages to mark, in the order to list them. A quote is looked for from its own page onwards,
  // and spans are placed where they say. New marks equal to the last ones are not looked for again.
  marks: Mark[];
  // The selected mark. The viewer takes the reader to it once it is found, and again each time the
  // caller selects another. When absent or not among the marks, the viewer selects the first mark not
  // known to be missing from the document.
  selectedMarkId?: string;
  // Called with the mark the person selects, in the document or in the list of passages.
  onSelectMark?: (id: string) => void;
  // Told the passage the person selected in the document, when they press the Comment button the viewer
  // offers under it. Without it, there is no Comment button.
  onAnnotate?: (anchor: TextAnchor) => void;
  // What to show under a passage, such as the comments on it, given the mark and whether it is selected.
  renderMarkDetails?: (mark: Mark, selected: boolean) => ReactNode;
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
  const { url, page, selectedMarkId, onSelectMark, onAnnotate, renderMarkDetails, title, container, onClose } = props;
  const marksKey = getMarksKey(props.marks);
  const marks = useMemo(() => JSON.parse(marksKey) as Mark[], [marksKey]);
  const [chosenId, setChosenId] = useState<string | undefined>(undefined);
  // The mark last reported to the caller. The caller selecting it in turn does not move the reader.
  const reportedId = useRef<string | undefined>(undefined);
  // The mark the caller last selected. A change to the marks does not move the reader to it again.
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
  const selectedMark = marks.find(mark => mark.id === selectedId);
  const view = usePdfView(pdf, {
    page: page ?? (selectedMark === undefined ? undefined : getMarkPage(selectedMark)),
    marks,
    places,
    selectedId,
    onSelectMark: select,
  });
  const { showMark } = view;
  const selectedText = useSelectedText(view.shown, onAnnotate !== undefined);
  const { dismiss } = selectedText;
  // Clears the selection once the passage is passed on, so the mark the caller makes of it shows.
  const annotate = pdf === undefined || onAnnotate === undefined
    ? undefined
    : async (spans: MarkSpan[]) => {
      const pages = await Promise.all(spans.map(span => pdf.getPageTextWithLines(span.page)));
      onAnnotate(getTextAnchor(spans, page => pages[spans.findIndex(span => span.page === page)]));
      dismiss();
      document.getSelection()?.removeAllRanges();
    };
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
      renderMarkDetails={renderMarkDetails}
      selectedText={selectedText}
      onAnnotate={annotate}
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

// Below this width, the document's column has no room for the search field beside the page and zoom
// controls. A button in the toolbar opens it in their place instead.
const SEARCH_FIELD_ROOM = 600;

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
  renderMarkDetails: PdfViewerProps["renderMarkDetails"];
  selectedText: ReturnType<typeof useSelectedText>;
  onAnnotate: ((spans: MarkSpan[]) => Promise<void>) | undefined;
  // Moves the viewer between its container and the dialog, when it has a container.
  placeButton: ReactNode;
  // In the dialog on a phone, the document spans the full screen width, past the dialog's padding.
  edgeToEdge: boolean;
  onClose: () => void;
}

function ViewerBody(props: ViewerBodyProps) {
  const { titleId, title, marks, places, selectedId, onSelect, openError, onRetry, view } = props;
  const { renderMarkDetails, selectedText, onAnnotate, placeButton, edgeToEdge, onClose } = props;
  const { page, pageCount, setFrame, showPage, showMark, zoomIn, zoomOut, fitWidth } = view;
  const { search, find, findAgain } = view;
  const [body, setBody] = useState<HTMLElement | null>(null);
  const narrow = useNarrowViewer(body);
  const [column, setColumn] = useState<HTMLElement | null>(null);
  const compact = useNarrowerThan(column, SEARCH_FIELD_ROOM);
  // Without room for it, the search takes the toolbar's place while it is open or holds a query. It takes
  // the focus, and gives it back to the button that opened it once closed.
  const [searchOpen, setSearchOpen] = useState(false);
  const searchInPlace = compact && (searchOpen || search.query !== "");
  const searchButtonRef = useRef<HTMLButtonElement>(null);
  const closingSearch = useRef(false);
  const searchInputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (searchInPlace) {
      searchInputRef.current?.focus();
      searchInputRef.current?.select();
    } else if (closingSearch.current) {
      closingSearch.current = false;
      searchButtonRef.current?.focus();
    }
  }, [searchInPlace]);
  const closeSearch = () => {
    closingSearch.current = true;
    find("");
    setSearchOpen(false);
  };
  // Ctrl+F, or Cmd+F, searches the document while focus is in the viewer, rather than the page. Escape
  // hides the Comment button before it closes anything.
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Escape" && selectedText.selected !== undefined) {
      event.stopPropagation();
      selectedText.dismiss();
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f" && pageCount !== undefined) {
      event.preventDefault();
      setSearchOpen(compact);
      searchInputRef.current?.focus();
      searchInputRef.current?.select();
    }
  };
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
        quote={getMarkText(selected, places[selected.id])}
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
        details={renderMarkDetails?.(selected, true)}
        onShowPassage={() => showMark(selected.id)}
      />
    );

  return (
    <Box
      ref={setBody}
      onKeyDown={onKeyDown}
      sx={{ display: "flex", flexDirection: "column", flex: "1 1 auto", minHeight: 0 }}
    >
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
        {search.outcome === "none" && selected !== undefined
          ? getPassageStatus(status, places[selected.id], several ? among : undefined)
          : search.announcement}
      </Box>
      <Box sx={{ display: "flex", gap: 2, flex: "1 1 auto", minHeight: 0 }}>
        <Box ref={setColumn} sx={{ display: "flex", flexDirection: "column", flex: "1 1 auto", minWidth: 0 }}>
          {searchInPlace
            ? (
              <Box sx={{ display: "flex", alignItems: "center", mb: 1 }}>
                <SearchField
                  search={search}
                  inputRef={searchInputRef}
                  onFind={find}
                  onFindAgain={findAgain}
                  onClose={closeSearch}
                />
              </Box>
            )
            : (
              <PdfToolbar
                page={page}
                pageCount={pageCount}
                search={compact
                  ? (
                    <IconButton
                      ref={searchButtonRef}
                      aria-label="Search the document"
                      disabled={pageCount === undefined}
                      onClick={() => setSearchOpen(true)}
                    >
                      <SearchIcon />
                    </IconButton>
                  )
                  : (
                    <SearchField
                      search={search}
                      inputRef={searchInputRef}
                      disabled={pageCount === undefined}
                      onFind={find}
                      onFindAgain={findAgain}
                    />
                  )}
                onShowPage={showPage}
                onZoomIn={zoomIn}
                onZoomOut={zoomOut}
                onFitWidth={fitWidth}
              />
            )}
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
          <PdfDocumentView setFrame={setFrame} edgeToEdge={edgeToEdge} outlineSelected={several}>
            {selectedText.selected === undefined || onAnnotate === undefined
              ? null
              : (
                <CommentButton
                  at={selectedText.selected}
                  onPress={selectedText.hold}
                  onComment={spans => void onAnnotate(spans)}
                />
              )}
          </PdfDocumentView>
        </Box>
        {several && !narrow
          ? (
            <MarkList
              marks={ordered}
              places={places}
              status={status}
              selectedId={selected?.id}
              renderDetails={renderMarkDetails}
              onSelect={showSelected}
            />
          )
          : null}
      </Box>
    </Box>
  );
}
